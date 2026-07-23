package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.entity.UserProfileSnapshotEntity
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.data.util.runCatchingCancellable
import com.trae.social.core.profiling.capture.ProfilingGate
import com.trae.social.core.profiling.feedback.CachedProfileLoader
import com.trae.social.core.profiling.feedback.ProfileCache
import com.trae.social.core.profiling.mapping.ProfileMappers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基础分析触发器（#146 第二层）。
 *
 * 双阈值触发：事件计数 >= [countThreshold]（100）或 距上次 >= [timeThresholdMs]（1h），
 * 先到即触发，debounce 30s。前台进入时强制检查一次。
 *
 * 全量重算兜底：每 6h 触发 FULL_RECOMPUTE 修正浮点漂移。
 */
@Singleton
class BasicProfileTrigger @Inject constructor(
    private val userActionDao: UserActionDao,
    private val userProfileDao: UserProfileDao,
    private val gate: ProfilingGate,
    private val eventTextPreParser: EventTextPreParser,
    private val cache: ProfileCache,
    private val loader: CachedProfileLoader,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var lastTriggerAt: Long = 0L
    @Volatile private var lastFullRecomputeAt: Long = 0L
    @Volatile private var pending: Boolean = false

    init {
        // 第七轮 review M2 修复：maybeTrigger 原为死代码（全仓无调用方），导致用户长停留前台
        // 时事件累积超 COUNT_THRESHOLD 或距上次分析超 TIME_THRESHOLD_MS 也永不触发，
        // 必须 kill+重启 App 才能重算画像。改为周期性自触发（每 CHECK_INTERVAL_MS 检查一次
        // 双阈值），使基础分析在用户不离开前台的情况下也能按双阈值触发。
        // forceCheckOnForeground 仍负责冷启动首次触发（onResume 调用）。
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(CHECK_INTERVAL_MS)
                maybeTrigger()
            }
        }
    }

    /** 由 Tracker 批写后或前台进入时调用，按双阈值 + debounce 决定是否触发分析。 */
    fun maybeTrigger() {
        if (!gate.isEnabled()) return
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val since = maxOf(lastTriggerAt, now - TIME_THRESHOLD_MS)
                val count = userActionDao.countSince(since)
                val byTime = (now - lastTriggerAt) >= TIME_THRESHOLD_MS
                if (count >= COUNT_THRESHOLD || byTime) {
                    scheduleCompute(now)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 第七轮 review M5 一致性修复：CancellationException 必须重抛。
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "BasicProfileTrigger 检查失败")
            }
        }
    }

    /** 前台进入时强制检查一次（不等阈值）。 */
    fun forceCheckOnForeground() {
        if (!gate.isEnabled()) return
        scope.launch { scheduleCompute(System.currentTimeMillis(), force = true) }
    }

    private suspend fun scheduleCompute(now: Long, force: Boolean = false) {
        // 第二轮 review Major 1 修复：原判断 `pending && now - lastTriggerAt < DEBOUNCE_MS`
        // 用 AND 合并两条件,而 lastTriggerAt 仅在 compute 完成后才更新,
        // 当 lastTriggerAt 是旧值时第二条件恒 false,pending=true 仍可绕过 → 并发执行多次 compute。
        // 修正为 OR 语义:pending(已正在 compute) 阻断并发,时间窗口阻断 compute 刚结束即重算。
        // 同时在 mutex 内同步更新 lastTriggerAt 标记 debounce 窗口起点,关闭 check-then-act 竞争窗口。
        //
        // #309 修复：原实现把 `if (pending) return` 包在 `if (!force)` 内，force=true 路径
        // 跳过 pending 检查 → forceCheckOnForeground 在 compute 进行中再次被调用时，
        // 两次 compute 并发执行，可能并发读写 lastFullRecomputeAt 与 Room 快照。
        // 修正：pending 并发保护对 force / 非 force 路径统一生效；force 仅跳过时间阈值检查
        // （force 语义是"不等阈值立即检查"，不是"无视并发强行重算"）。
        mutex.withLock {
            if (pending) return
            if (!force) {
                if (System.currentTimeMillis() - lastTriggerAt < DEBOUNCE_MS) return
                // 标记 debounce 窗口起点,使并发的 scheduleCompute 调用在此窗口内被拦截
                lastTriggerAt = System.currentTimeMillis()
            }
            pending = true
        }
        delay(DEBOUNCE_MS)
        runCatchingCancellable { compute(now) }
            .onFailure { Timber.w(it, "基础分析计算失败") }
        mutex.withLock {
            pending = false
            // compute 完成后再次刷新 lastTriggerAt,使"刚结束的 compute"在 DEBOUNCE_MS 内
            // 不会被紧随其后的触发立即重算
            lastTriggerAt = System.currentTimeMillis()
        }
    }

    private suspend fun compute(now: Long) {
        val windowStart = now - ANALYSIS_WINDOW_MS
        val entities = userActionDao.queryBetween(windowStart, now)
        if (entities.isEmpty()) return
        val events = entities.mapNotNull { it.toDomain() }
        if (events.isEmpty()) return
        // #146 算法优化：对携带文本的事件（PUBLISH_TWEET / TWEET_COMMENT）进行 LLM 预解析，
        // 提取 textTopic / textSentiment / textIntent 写回 extra，供 BasicProfileAnalyzer 融合消费。
        // 已解析过的事件跳过（持久化缓存），LLM 不可用时优雅降级返回原始事件。
        val enrichedEvents = eventTextPreParser.enrichWithTextSignals(events)
        val previous = userProfileDao.latestSnapshot()?.toDomain()
        val doFullRecompute = (now - lastFullRecomputeAt) >= FULL_RECOMPUTE_INTERVAL_MS
        val snapshot = if (doFullRecompute && previous != null) {
            // 全量重算：忽略 previous 增量，从原始事件重算
            lastFullRecomputeAt = now
            BasicProfileAnalyzer.analyze(enrichedEvents, previous = null, now = now)
        } else {
            BasicProfileAnalyzer.analyze(enrichedEvents, previous = previous, now = now)
        }
        val source = if (doFullRecompute) SOURCE_FULL_RECOMPUTE else SOURCE_INCREMENTAL
        userProfileDao.insertSnapshot(snapshot.toEntity(source))
        // 第七轮 review M4 一致性：写快照后失效缓存 + 刷新 loader，使读侧立即感知新快照。
        // 原实现缺少此步骤，导致 ProfileCache 30s TTL 内读侧返回旧值。
        cache.invalidate()
        loader.refresh()
        Timber.i("基础分析完成 source=%s events=%d", source, enrichedEvents.size)
    }

    /**
     * 第七轮 review M4 修复：写入冷启动 seeding 快照。
     *
     * onboarding 完成时调用，将用户即将看到的活跃虚拟账号的职业作为初始兴趣种子写入
     * source=COLD_START_SEEDING 的快照。使 [com.trae.social.core.profiling.feedback.UserProfileReadAccess]
     * 在冷启动期（eventCount < COLD_START_THRESHOLD）通过 coldStartSeeding() 返回非空兴趣向量，
     * TweetGenerationWorker 能在 driven 组注入"用户近期关注话题"提示，实现冷启动期即个性化。
     *
     * 幂等：若已存在 COLD_START_SEEDING 快照则跳过（避免重复 onboarding 写入多条）。
     *
     * @param interests 兴趣向量（key=主题/职业，value=权重，无需归一化，内部会归一化）
     */
    suspend fun seedColdStartSnapshot(interests: Map<String, Double>) {
        if (!gate.isEnabled()) return
        // 幂等：已存在则跳过
        if (userProfileDao.earliestColdStartSnapshot() != null) {
            Timber.i("冷启动 seeding 快照已存在，跳过写入")
            return
        }
        val now = System.currentTimeMillis()
        val normalized = normalizeInterests(interests)
        if (normalized.isEmpty()) {
            Timber.w("冷启动 seeding 兴趣向量为空，跳过写入")
            return
        }
        val snapshot = buildColdStartSnapshot(normalized, now)
        userProfileDao.insertSnapshot(snapshot.toEntity(SOURCE_COLD_START_SEEDING))
        cache.invalidate()
        loader.refresh()
        Timber.i("冷启动 seeding 写入完成 interests=%s", normalized.keys)
    }

    /** 归一化兴趣向量：过滤零/负值后按总和归一化到 [0,1]。 */
    private fun normalizeInterests(interests: Map<String, Double>): Map<String, Double> {
        val filtered = interests.filterValues { it > 0.0 }
        if (filtered.isEmpty()) return emptyMap()
        val sum = filtered.values.sum()
        return if (sum <= 0.0) filtered else filtered.mapValues { it.value / sum }
    }

    /**
     * 构建冷启动 seeding 快照：仅填充 interestVector，其余维度为零/空（无事件数据）。
     * confidence 全部设为低值（0.1），使 feedbackWeights 经置信度降权后接近零，
     * 避免冷启动期弱数据强干预；interestVector 置信度略高（0.3）因有 onboarding 种子。
     */
    private fun buildColdStartSnapshot(interests: Map<String, Double>, now: Long): UserProfileSnapshot {
        val zeroConfidence = com.trae.social.core.data.model.ProfileConfidence(
            activeHours = 0.0,
            interestVector = 0.3,
            interactionTendency = 0.1,
            browseDepth = 0.1,
            postingCadence = 0.1,
            socialStyle = 0.1,
            periodicity = 0.1,
        )
        return UserProfileSnapshot(
            activeHours = emptyList(),
            interestVector = interests,
            interactionTendency = com.trae.social.core.data.model.InteractionTendency(
                likeRate = 0.0, commentRate = 0.0, retweetRate = 0.0, bookmarkRate = 0.0
            ),
            browseDepth = com.trae.social.core.data.model.BrowseDepth(
                avgDwellMs = 0L, tweetsPerSession = 0.0, scrollDepthRatio = 0.0
            ),
            postingCadence = com.trae.social.core.data.model.PostingCadence(
                postFrequency = 0.0, postingHours = emptyList(), avgCaptionLength = 0, avgImageCount = 0.0
            ),
            socialStyle = com.trae.social.core.data.model.SocialStyle(
                activeFollowRatio = 0.0, avgInteractionDelayMs = 0L
            ),
            periodicity = com.trae.social.core.data.model.Periodicity(
                weekdayScore = 0.0, weekendScore = 0.0, isWeekendDominant = false
            ),
            confidence = zeroConfidence,
            evidence = com.trae.social.core.data.model.ProfileEvidence(
                eventCount = 0, anomalyCount = 0, topThemes = emptyList(),
                topActiveHours = emptyList(), sampleTweetIds = emptyList()
            ),
            computedAt = now,
            eventWindowStart = now,
            eventWindowEnd = now,
        )
    }

    private fun com.trae.social.core.data.entity.UserActionEventEntity.toDomain() =
        ProfileMappers.run { toDomain() }

    private fun UserProfileSnapshotEntity.toDomain(): UserProfileSnapshot? =
        ProfileMappers.run { toDomain() }

    private fun UserProfileSnapshot.toEntity(source: String) =
        ProfileMappers.run { toEntity(source) }

    private companion object {
        const val COUNT_THRESHOLD = 100
        const val TIME_THRESHOLD_MS = 60 * 60 * 1000L // 1h
        const val DEBOUNCE_MS = 30_000L // 30s
        const val ANALYSIS_WINDOW_MS = 14L * 24 * 60 * 60 * 1000 // TTL 14 天
        const val FULL_RECOMPUTE_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6h
        /** 第七轮 review M2 修复：周期性自触发间隔，确保 maybeTrigger 不再是死代码。 */
        const val CHECK_INTERVAL_MS = 5L * 60 * 1000 // 5 min
        const val SOURCE_INCREMENTAL = "INCREMENTAL"
        const val SOURCE_FULL_RECOMPUTE = "FULL_RECOMPUTE"
        const val SOURCE_COLD_START_SEEDING = "COLD_START_SEEDING"
    }
}
