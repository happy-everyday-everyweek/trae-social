package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.entity.UserProfileSnapshotEntity
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.profiling.capture.ProfilingGate
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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var lastTriggerAt: Long = 0L
    @Volatile private var lastFullRecomputeAt: Long = 0L
    @Volatile private var pending: Boolean = false

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
        mutex.withLock {
            if (!force) {
                if (pending) return
                if (System.currentTimeMillis() - lastTriggerAt < DEBOUNCE_MS) return
                // 标记 debounce 窗口起点,使并发的 scheduleCompute 调用在此窗口内被拦截
                lastTriggerAt = System.currentTimeMillis()
            }
            pending = true
        }
        delay(DEBOUNCE_MS)
        runCatching { compute(now) }
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
        Timber.i("基础分析完成 source=%s events=%d", source, enrichedEvents.size)
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
        const val SOURCE_INCREMENTAL = "INCREMENTAL"
        const val SOURCE_FULL_RECOMPUTE = "FULL_RECOMPUTE"
    }
}
