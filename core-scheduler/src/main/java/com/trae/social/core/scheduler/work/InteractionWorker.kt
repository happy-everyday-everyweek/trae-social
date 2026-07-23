package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.InteractionType
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.InteractionRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.data.model.ScenarioIds
import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.profiling.capture.SessionManager
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.core.profiling.feedback.FeedbackController
import com.trae.social.core.profiling.feedback.UserProfileReadAccess
import com.trae.social.core.scheduler.ratelimit.SchedulerRateLimiter
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.RulesetEngine
import com.trae.social.llm.interceptor.RateLimitedException
import com.trae.social.llm.prompt.CommentPromptBuilder
import com.trae.social.llm.prompt.TweetPromptBuilder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 互动排程 Worker（SubTask 8.3）。
 *
 * 当一条新推文写入后，由调度器入队本 Worker：
 * 1. 加载被评推文与作者；
 * 2. 从虚拟账号池中按人设相似度（bio 关键词 + 职业重合）筛选 3-8 个评论者；
 * 3. 按概率分配互动类型（LIKE 50% / COMMENT 30% / RETWEET 15% / FOLLOW 5%）；
 * 4. 按对数正态分布为每条互动排程延迟触发时刻；
 * 5. 评论者批量调用一次 LLM 生成评论文本；
 * 6. 写 [InteractionEntity]（scheduledAt = now + delay）；
 * 7. 更新推文计数（最终计数由 [PendingInteractionWorker] 在执行时累加）。
 *
 * 注意：本 Worker 仅负责"即时排程"，实际延迟执行由 [PendingInteractionWorker] 周期扫描完成。
 *
 * #283：[doWork] 仅保留参数校验与异常兜底，主流程拆分到 [runInteractionScheduling] 及
 * `load*` / `build*` / `emit*` 私有方法，便于单点维护。
 */
@HiltWorker
class InteractionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val accountRepository: AccountRepository,
    private val tweetRepository: TweetRepository,
    private val interactionRepository: InteractionRepository,
    private val rulesetEngine: RulesetEngine,
    private val rateLimiter: SchedulerRateLimiter,
    private val logDao: com.trae.social.core.data.dao.SchedulerLogDao,
    // #146 A/E：反哺层场景 3（interactionAffinity）+ 场景 8（interactionTiming）
    private val feedbackController: FeedbackController,
    private val readAccess: UserProfileReadAccess,
    private val userActionTracker: UserActionTracker,
    private val sessionManager: SessionManager,
) : CoroutineWorker(appContext, params) {

    private val commentBuilder = CommentPromptBuilder()

    /**
     * 各 step 方法的统一返回类型：要么携带产物放行（[Proceed]），要么提前返回 [Result]（[Abort]）。
     * 用泛型 + `out` 协变，使 `Abort` 可作为任意 `Outcome<T>` 的子类型。
     */
    private sealed interface Outcome<out T> {
        data class Proceed<out T>(val value: T) : Outcome<T>
        data class Abort(val result: Result) : Outcome<Nothing>
    }

    /** 推文 + 作者加载产物。 */
    private data class TweetAuthor(val tweet: TweetEntity, val author: AccountEntity)

    /** 互动排程计划：承载 doWork 后续打标 / 即时入队 / 日志所需的所有中间产物。 */
    private data class InteractionPlan(
        val sessionId: String,
        val drivenScenario3: Boolean,
        val drivenScenario4: Boolean,
        val drivenScenario8: Boolean,
        val assignments: List<InteractionAssignment>,
        val commentAssignments: List<InteractionAssignment>,
        val interactions: List<InteractionEntity>,
        val now: Long,
    )

    override suspend fun doWork(): Result {
        val tweetId = inputData.getString(WorkerKeys.KEY_TWEET_ID)
        if (tweetId.isNullOrBlank()) {
            return Result.failure(workDataOf(WorkerKeys.KEY_ERROR to "missing tweetId"))
        }
        val started = System.currentTimeMillis()
        return try {
            runInteractionScheduling(tweetId, started)
        } catch (e: RateLimitedException) {
            // IMPL-19：429 限流直接跳过，不重试，避免浪费配额
            Timber.w("InteractionWorker 遇到限流，跳过 tweetId=%s retryAfter=%s", tweetId, e.retryAfterSeconds)
            logSchedulerEvent(tweetId, started, "rate_limited", e.message)
            Result.success(workDataOf(WorkerKeys.KEY_RESULT to "rate_limited"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 第六轮 review M3 修复：CancellationException 必须重抛，否则 WorkManager 取消 Worker 时
            // 协程无法正确传播取消信号，导致 doWork 卡在 catch(t: Throwable) 内继续执行返回 Result.retry。
            throw e
        } catch (t: Throwable) {
            Timber.e(t, "InteractionWorker 执行失败 tweetId=%s", tweetId)
            val error = t.message ?: t.javaClass.simpleName
            logSchedulerEvent(tweetId, started, "error", error)
            if (runAttemptCount >= WorkerConstants.MAX_RUN_ATTEMPTS) {
                Result.failure(workDataOf(WorkerKeys.KEY_ERROR to error))
            } else {
                Result.retry()
            }
        }
    }

    /**
     * 主流程编排（原 doWork 的 try 体）。
     */
    private suspend fun runInteractionScheduling(tweetId: String, started: Long): Result {
        // 1. 查推文与作者
        val ta = loadTweetAndAuthor(tweetId, started)
        if (ta is Outcome.Abort) return ta.result
        val (tweet, author) = (ta as Outcome.Proceed<TweetAuthor>).value

        // 2-5. 选评论者 / 分配互动类型 / 批量生成评论 / 写 InteractionEntity
        val plan = buildInteractionPlan(tweet, author, started)
        if (plan is Outcome.Abort) return plan.result
        val p = (plan as Outcome.Proceed<InteractionPlan>).value

        // 6. 反哺层打标（场景 3/8 + 场景 4）
        emitScenarioMarkers(tweet, p)

        // #78：短延迟的 LIKE/FOLLOW/COMMENT/RETWEET 用 OneTimeWorkRequest + setInitialDelay
        // 直接调度，避免受 PendingInteractionWorker 15 分钟周期限制，使互动更像真人即时反应
        val shortDelayInteractions = p.interactions.filter {
            it.type in setOf(
                InteractionType.LIKE,
                InteractionType.FOLLOW,
                InteractionType.COMMENT,
                InteractionType.RETWEET,
            )
        }.filter {
            (it.scheduledAt - p.now) <= SHORT_DELAY_THRESHOLD_MS
        }
        if (shortDelayInteractions.isNotEmpty()) {
            enqueueImmediateInteractionExecution(shortDelayInteractions, p.now)
        }

        // 7. 写调度日志
        val status = "scheduled_${p.interactions.size}"
        logSchedulerEvent(author.id, started, status, null)
        return Result.success(
            workDataOf(
                WorkerKeys.KEY_RESULT to status,
                WorkerKeys.KEY_TWEET_ID to tweet.id,
            )
        )
    }

    /** 1. 加载被评推文与作者；推文或作者缺失时跳过（写日志后返回 success）。 */
    private suspend fun loadTweetAndAuthor(tweetId: String, started: Long): Outcome<TweetAuthor> {
        val tweet = tweetRepository.getById(tweetId)
        if (tweet == null) {
            Timber.w("推文 %s 不存在，跳过互动排程", tweetId)
            val status = "skipped_no_tweet"
            logSchedulerEvent(tweetId, started, status, "tweet not found")
            return Outcome.Abort(Result.success(workDataOf(WorkerKeys.KEY_RESULT to status)))
        }
        val author = accountRepository.getById(tweet.authorId)
        if (author == null) {
            val status = "skipped_no_author"
            logSchedulerEvent(tweet.authorId, started, status, "author not found")
            return Outcome.Abort(Result.success(workDataOf(WorkerKeys.KEY_RESULT to status)))
        }
        return Outcome.Proceed(TweetAuthor(tweet, author))
    }

    /**
     * 2-5. 选评论者 → 分配互动类型 → 批量生成评论 → 写 [InteractionEntity]。
     *
     * 返回 [InteractionPlan] 供后续打标与即时入队复用；无评论者时跳过。
     */
    private suspend fun buildInteractionPlan(
        tweet: TweetEntity,
        author: AccountEntity,
        started: Long,
    ): Outcome<InteractionPlan> {
        val sessionId = sessionManager.currentSessionId() ?: tweet.id
        // #146 A/E 场景 3：判断本次是否 driven（画像驱动互动账号选择）
        val drivenScenario3 = feedbackController.shouldApply(ScenarioIds.INTERACTION_AFFINITY, sessionId)
        val interestVector = if (drivenScenario3) readAccess.interestVector() else emptyMap()
        val candidates = selectCommenters(
            tweet.authorId, author.profession, author.bio, drivenScenario3, interestVector,
        )
        if (candidates.isEmpty()) {
            val status = "skipped_no_commenters"
            logSchedulerEvent(tweet.authorId, started, status, null)
            return Outcome.Abort(Result.success(workDataOf(WorkerKeys.KEY_RESULT to status)))
        }

        val now = System.currentTimeMillis()
        // #116：使用 nanoTime + accountId.hashCode() 作为种子，
        // 避免毫秒级种子在并发时产生相同互动模式
        val random = Random(System.nanoTime() xor tweet.authorId.hashCode().toLong())
        // #146 A/E 场景 8：判断互动时机是否 driven（画像驱动排程时段）
        val drivenScenario8 = feedbackController.shouldApply(ScenarioIds.INTERACTION_TIMING, sessionId)
        val userActiveHours = if (drivenScenario8) readAccess.activeHours() else emptyList()
        // #146 A/E 场景 4：判断评论文本是否 driven（画像驱动评论内容）
        val drivenScenario4 = feedbackController.shouldApply(ScenarioIds.COMMENT_PERSONA, sessionId)
        val assignments = candidates.map { account ->
            val type = assignInteractionType(random)
            val delayMillis = scheduleDelayFor(type, random, drivenScenario8, userActiveHours)
            InteractionAssignment(
                accountId = account.id,
                type = type,
                delayMillis = delayMillis,
                persona = TweetPromptBuilder.PersonaInput.from(account),
            )
        }

        // 4. 评论批量化：收集需评论者，一次调用 LLM
        val commentAssignments = assignments.filter { it.type == InteractionType.COMMENT }
        val commentTextsByAccount: Map<String, String> = if (commentAssignments.isNotEmpty()) {
            generateComments(tweet, author, commentAssignments, random, drivenScenario4)
        } else {
            emptyMap()
        }

        // 5. 写 InteractionEntity（排程）
        val interactions = assignments.map { assignment ->
            InteractionEntity(
                id = UUID.randomUUID().toString(),
                tweetId = tweet.id,
                accountId = assignment.accountId,
                type = assignment.type,
                content = commentTextsByAccount[assignment.accountId],
                createdAt = now,
                scheduledAt = now + assignment.delayMillis,
                executedAt = null,
            )
        }
        interactionRepository.scheduleInteractions(interactions)

        return Outcome.Proceed(
            InteractionPlan(
                sessionId = sessionId,
                drivenScenario3 = drivenScenario3,
                drivenScenario4 = drivenScenario4,
                drivenScenario8 = drivenScenario8,
                assignments = assignments,
                commentAssignments = commentAssignments,
                interactions = interactions,
                now = now,
            )
        )
    }

    /**
     * 6. 反哺层打标——场景 3/8（互动账号选择 + 时段）+ 场景 4（评论文本）。
     *
     * #146 A：为本次互动排程发 scenario 事件，供 computeFeedbackEffect 做 A/B 回测。
     * 场景 3/8 共用一次排程，以场景 3 为主标记（互动账号选择），场景 8（时段）作为同次排程的附属维度。
     * drivenByProfile 标记本次排程是否受画像驱动；control 组同样落事件以便计算互动率 delta。
     * 第六轮 review B1/B2 修复：isScenarioMarker=true 标记本事件为调度器打标（非真实用户互动），
     * 供 UserProfileAggregator.computeScenarioStats 区分"曝光标记"与"真实互动"，避免 delta 恒为 0；
     * 供 BasicProfileAnalyzer.analyze 过滤掉调度器打标，避免污染用户画像。
     *
     * #146 A/E 场景 4 commentPersona：评论文本 driven 单独打标（与场景 3/8 解耦，
     * 因为评论内容是否受画像驱动独立于账号选择/时段）。仅当本次排程含评论任务时落事件，
     * 供 computeFeedbackEffect 回测评论文本质量与互动率 delta。
     */
    private fun emitScenarioMarkers(tweet: TweetEntity, p: InteractionPlan) {
        val driven3 = p.drivenScenario3 || p.drivenScenario8
        runCatching {
            userActionTracker.trackNow(
                UserActionEvent(
                    // 第七轮 review M6 修复：用稳定 id 替代 UUID.randomUUID()。
                    // Worker 重试时 tweetId 不变（来自 inputData），故同一推文的互动排程
                    // 重试产生相同 id，Room @PrimaryKey + REPLACE 保证幂等。
                    id = "marker_s3_${tweet.id}",
                    type = UserActionType.TWEET_LIKE,
                    screen = "interaction_schedule",
                    targetId = tweet.id,
                    targetKind = "tweet",
                    extra = mapOf(
                        "scenarioId" to kotlinx.serialization.json.JsonPrimitive(ScenarioIds.INTERACTION_AFFINITY),
                        "drivenByProfile" to kotlinx.serialization.json.JsonPrimitive(driven3),
                        "group" to kotlinx.serialization.json.JsonPrimitive(if (driven3) "driven" else "control"),
                        "interactionCount" to kotlinx.serialization.json.JsonPrimitive(p.interactions.size),
                        "isScenarioMarker" to kotlinx.serialization.json.JsonPrimitive(true),
                    ),
                    occurredAt = p.now,
                    session = p.sessionId,
                )
            )
        }.onFailure { Timber.w(it, "#146 场景 3/8 打标失败") }

        if (p.commentAssignments.isEmpty()) return
        runCatching {
            userActionTracker.trackNow(
                UserActionEvent(
                    // 第七轮 review M6 修复：用稳定 id 替代 UUID.randomUUID()（同场景 3）。
                    id = "marker_s4_${tweet.id}",
                    type = UserActionType.TWEET_COMMENT,
                    screen = "interaction_schedule_comment",
                    targetId = tweet.id,
                    targetKind = "tweet",
                    extra = mapOf(
                        "scenarioId" to kotlinx.serialization.json.JsonPrimitive(ScenarioIds.COMMENT_PERSONA),
                        "drivenByProfile" to kotlinx.serialization.json.JsonPrimitive(p.drivenScenario4),
                        "group" to kotlinx.serialization.json.JsonPrimitive(if (p.drivenScenario4) "driven" else "control"),
                        "commentCount" to kotlinx.serialization.json.JsonPrimitive(p.commentAssignments.size),
                        "isScenarioMarker" to kotlinx.serialization.json.JsonPrimitive(true),
                    ),
                    occurredAt = p.now,
                    session = p.sessionId,
                )
            )
        }.onFailure { Timber.w(it, "#146 场景 4 打标失败") }
    }

    /**
     * 从虚拟账号中按人设相似度筛选 3-8 个评论者。
     *
     * 翻页加载全部虚拟账号（约 220 个），按 bio 关键词 + 职业重合度评分，
     * 取相似度 Top targetCount 个作为评论者，打乱顺序以随机化各账号分配到的互动类型
     * （LIKE/COMMENT/RETWEET/FOLLOW）。候选不足时回退到全量随机选取。
     */
    private suspend fun selectCommenters(
        authorId: String,
        authorProfession: String,
        authorBio: String,
        drivenByProfile: Boolean,
        interestVector: Map<String, Double>,
    ): List<AccountEntity> {
        // #106：移除 MAX_ACCOUNT_PAGES 硬编码上限，循环直到无更多数据
        val all = mutableListOf<AccountEntity>()
        var page = 1
        while (true) {
            val batch = runCatching { accountRepository.getAccounts(page) }.getOrDefault(emptyList())
            if (batch.isEmpty()) break
            all.addAll(batch.filter { it.isVirtual && it.id != authorId })
            page++
        }
        if (all.isEmpty()) return emptyList()

        val authorKeywords = extractKeywords(authorBio + " " + authorProfession)
        // #146 A/E 场景 3：driven 组在原相似度评分基础上，叠加用户兴趣向量匹配加分，
        // 使选出的评论者更贴近用户关注领域（画像驱动互动账号选择）；
        // control 组不加 interestVector 项，保留原始相似度排序，供 computeFeedbackEffect 做 A/B 回测。
        val scored = all.map { account ->
            val overlap = countOverlap(authorKeywords, extractKeywords(account.bio + " " + account.profession))
            val professionMatch = if (account.profession == authorProfession) 2 else 0
            // driven 组：评论者 bio/profession 命中用户兴趣关键词则按兴趣权重加分
            val interestBoost = if (drivenByProfile) {
                val accountKw = extractKeywords(account.bio + " " + account.profession)
                accountKw.sumOf { kw -> (interestVector[kw] ?: 0.0) } * 3.0
            } else 0.0
            account to (overlap + professionMatch + interestBoost)
        }
        // #82：按相似度分数降序选取。targetCount = min(MAX, max(MIN, 全量 30%))，
        // 即受 MIN/MAX 约束的相似度 Top targetCount 个。
        val targetCount = min(MAX_COMMENTERS, max(MIN_COMMENTERS, (all.size * 0.3).toInt().coerceAtLeast(1)))
        // m2 修复：原 pool.shuffled().take(targetCount) 中 pool.size == targetCount，
        // take 为冗余空操作已移除；shuffled 保留用于打乱评论者顺序，进而随机化
        // 各账号在后续 assignInteractionType 中分到的互动类型。
        return scored.sortedByDescending { it.second }
            .take(targetCount)
            .map { it.first }
            .shuffled(Random(System.currentTimeMillis()))
    }

    private fun extractKeywords(text: String): Set<String> {
        return text.split(Regex("[\\s,，。、；;:：!！?？]+"))
            .filter { it.isNotBlank() && it.length >= 2 }
            .map { it.lowercase() }
            .toSet()
    }

    private fun countOverlap(a: Set<String>, b: Set<String>): Int = a.intersect(b).size

    /**
     * 按概率分配互动类型。
     */
    private fun assignInteractionType(random: Random): InteractionType {
        val r = random.nextDouble()
        return when {
            r < LIKE_THRESHOLD -> InteractionType.LIKE
            r < LIKE_THRESHOLD + COMMENT_THRESHOLD -> InteractionType.COMMENT
            r < LIKE_THRESHOLD + COMMENT_THRESHOLD + RETWEET_THRESHOLD -> InteractionType.RETWEET
            else -> InteractionType.FOLLOW
        }
    }

    /**
     * 按对数正态分布生成互动延迟（毫秒）（IMPL-20）。
     *
     * 使用 nextGaussian()*std+mean 在 log 空间采样后取指数，
     * 再 clamp 到 [minMs, maxMs]。
     *
     * - LIKE：30s - 5min
     * - COMMENT：2min - 15min
     * - RETWEET：5min - 30min
     * - FOLLOW：1min - 10min
     */
    private fun scheduleDelayFor(
        type: InteractionType,
        random: Random,
        drivenByProfile: Boolean,
        userActiveHours: List<Int>,
    ): Long {
        val (minMs, maxMs) = when (type) {
            InteractionType.LIKE -> 30_000L to 5 * 60_000L
            InteractionType.COMMENT -> 2 * 60_000L to 15 * 60_000L
            InteractionType.RETWEET -> 5 * 60_000L to 30 * 60_000L
            InteractionType.FOLLOW -> 60_000L to 10 * 60_000L
        }
        val logMin = ln(minMs.toDouble())
        val logMax = ln(maxMs.toDouble())
        val mean = (logMin + logMax) / 2
        val std = (logMax - logMin) / 4
        // IMPL-20：用 Box-Muller 变换实现真正的对数正态分布
        // kotlin.random.Random 无 nextGaussian()，用均匀随机数通过 Box-Muller 变换生成
        val u1 = random.nextDouble().coerceAtLeast(Double.MIN_VALUE)
        val u2 = random.nextDouble()
        val gaussian = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
            kotlin.math.cos(2.0 * kotlin.math.PI * u2)
        val logValue = mean + std * gaussian
        var raw = exp(logValue).toLong()
        if (raw < minMs) raw = minMs
        if (raw > maxMs) raw = maxMs
        // #146 A/E 场景 8：driven 组若当前不在用户活跃时段，则将互动延迟到下一个活跃时段，
        // 使 AI 互动在用户更可能在线的时段触达（画像驱动互动时机）；
        // control 组保持原始对数正态延迟，供 computeFeedbackEffect 回测时段匹配度差异。
        if (drivenByProfile && userActiveHours.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = now + raw
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            if (hour !in userActiveHours) {
                // 找下一个活跃小时（最多向前看 24 小时），将延迟延伸到该小时整点
                var nextHour = (hour + 1) % 24
                var steps = 0
                while (nextHour !in userActiveHours && steps < 24) {
                    nextHour = (nextHour + 1) % 24
                    steps++
                }
                if (nextHour in userActiveHours) {
                    cal.add(java.util.Calendar.HOUR_OF_DAY, (nextHour - hour + 24) % 24)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    val shifted = cal.timeInMillis - now
                    // 仅在合理范围内（不超过 maxMs 的 24 倍即约 12h）应用时段偏移，避免异常长延迟
                    if (shifted in minMs..(maxMs * 24)) raw = shifted
                }
            }
        }
        return raw
    }

    /**
     * 批量生成评论：一次 LLM 调用为所有评论者生成评论文本。
     *
     * #146 A/E 场景 4 commentPersona：当 [drivenScenario4] 为 true 时，注入用户兴趣 Top 主题
     * 到评论 prompt（[CommentPromptBuilder.UserTasteHint]），使评论文本在主题与措辞上贴近
     * 用户口味；control 组不注入，保留原始评论风格供 A/B 回测。
     */
    private suspend fun generateComments(
        tweet: TweetEntity,
        author: AccountEntity,
        commenters: List<InteractionAssignment>,
        random: Random,
        drivenScenario4: Boolean,
    ): Map<String, String> {
        if (commenters.isEmpty()) return emptyMap()
        // 第六轮 review M4 修复：与 TweetGenerationWorker/PersonaUpdateWorker 一致，使用带超时的 acquire，
        // 避免限流阻塞超过 WorkManager 默认 10 分钟超时上限导致 Worker 被强制终止。
        if (!rateLimiter.acquireWithTimeout(WorkerConstants.ACQUIRE_TIMEOUT_MS)) {
            Timber.i("InteractionWorker 生成评论限流等待超时，跳过评论内容")
            return emptyMap()
        }

        val personas = commenters.map { it.persona }
        // #146 场景 4：driven 组收集用户口味提示（兴趣 Top 主题 + 高权重映射 + 叙事摘要）
        val userTaste = if (drivenScenario4) collectUserTasteHint() else null
        val messages = commentBuilder.build(
            tweet = CommentPromptBuilder.TweetInput(
                text = tweet.text,
                authorName = author.displayName,
                authorProfession = author.profession,
            ),
            commenters = personas,
            userTaste = userTaste,
        )
        val raw = try {
            rulesetEngine.chatSync(
                messages = messages,
                config = ChatConfig(temperature = 0.9f, maxTokens = 512, jsonMode = true),
            )
        } catch (e: RateLimitedException) {
            // IMPL-19：429 限流向上抛出，由 doWork 统一捕获并跳过，不在此处吞掉
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "批量生成评论失败，跳过评论内容")
            return emptyMap()
        }
        val results = CommentPromptBuilder.parseCommentResults(raw, commenters.size)
        val mapping = mutableMapOf<String, String>()
        results.forEach { result ->
            val idx = result.commenterIndex
            if (idx in commenters.indices && result.text.isNotBlank()) {
                mapping[commenters[idx].accountId] = result.text.take(WorkerConstants.MAX_COMMENT_LENGTH)
            }
        }
        return mapping
    }

    /**
     * 收集用户口味提示（#146 场景 4）。
     *
     * 合并来源：
     * - interestVector：用户兴趣向量（已合并 theme overrides）；
     * - latestSnapshot.evidence.topThemes：观察到的 Top 主题（带 weight）；
     * - activeVersion.narrative：画像叙事摘要（前 120 字）。
     *
     * 任一来源缺失时仅降级，不阻断评论生成。
     */
    private fun collectUserTasteHint(): CommentPromptBuilder.UserTasteHint {
        val interestVector = runCatching { readAccess.interestVector() }.getOrDefault(emptyMap())
        val snapshot = runCatching { readAccess.latestSnapshot() }.getOrNull()
        val version = runCatching { readAccess.activeVersion() }.getOrNull()
        val topThemesFromSnapshot = snapshot?.evidence?.topThemes
            ?.sortedByDescending { it.weight }
            ?.take(8)
            ?.map { it.theme }
            ?: emptyList()
        val topThemes = (topThemesFromSnapshot + interestVector.keys)
            .distinct()
            .take(10)
        // 高权重映射：interestVector 为权威（已合并 overrides 并归一化），
        // snapshotWeights 仅补齐 interestVector 中缺失的主题；Kotlin Map + 右操作数覆盖左操作数，
        // 故 interestVector 放右侧。取 Top 8 用于 prompt 提示。
        val snapshotWeights = snapshot?.evidence?.topThemes
            ?.associate { it.theme to it.weight }
            ?: emptyMap()
        val mergedWeights = (snapshotWeights + interestVector)
            .filterValues { it > 0.0 }
            .entries
            .sortedByDescending { it.value }
            .take(8)
            .associate { it.key to it.value }
        val narrative = version?.narrative?.takeIf { it.isNotBlank() }
        return CommentPromptBuilder.UserTasteHint(
            topThemes = topThemes,
            topInterestWeights = mergedWeights,
            narrative = narrative,
        )
    }

    // #218：logSchedulerEvent 实现抽到 SchedulerLogger.log，此处保留薄包装统一 action 标识
    private suspend fun logSchedulerEvent(
        accountId: String,
        startedAt: Long,
        status: String,
        error: String?,
    ) {
        SchedulerLogger.log(logDao, "interaction_schedule", accountId, startedAt, status, error)
    }

    private data class InteractionAssignment(
        val accountId: String,
        val type: InteractionType,
        val delayMillis: Long,
        val persona: TweetPromptBuilder.PersonaInput,
    )

    /**
     * IMPL-21：为短延迟的互动入队即时执行 Worker。
     *
     * PendingInteractionWorker 是 15 分钟周期，LIKE 设计延迟 30s-5min 会最坏延迟到 20min。
     * #78：COMMENT/RETWEET 也纳入即时执行，阈值放宽到 30min 覆盖其延迟区间。
     * 此处为短延迟互动入队 OneTimeWorkRequest + setInitialDelay，由 WorkManager 精确触发。
     */
    private fun enqueueImmediateInteractionExecution(
        interactions: List<InteractionEntity>,
        now: Long,
    ) {
        runCatching {
            val workManager = androidx.work.WorkManager.getInstance(applicationContext)
            for (interaction in interactions) {
                val delayMs = (interaction.scheduledAt - now).coerceAtLeast(0L)
                val request = androidx.work.OneTimeWorkRequestBuilder<PendingInteractionWorker>()
                    .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .setConstraints(WorkerPolicies.networkConstraints)
                    .addTag(WorkerTags.INTERACTION)
                    .build()
                // #71：使用 enqueueUniqueWork 避免同一互动重复入队
                workManager.enqueueUniqueWork(
                    "pending_interaction_${interaction.id}",
                    androidx.work.ExistingWorkPolicy.KEEP,
                    request,
                )
            }
            Timber.i("IMPL-21: 为 %d 个短延迟互动入队即时执行", interactions.size)
        }.onFailure { Timber.w(it, "即时互动入队失败，回退到周期执行") }
    }

    private companion object {
        const val MIN_COMMENTERS = 3
        const val MAX_COMMENTERS = 8
        const val LIKE_THRESHOLD = 0.50
        const val COMMENT_THRESHOLD = 0.30
        const val RETWEET_THRESHOLD = 0.15
        /** #78：短延迟阈值，覆盖 COMMENT(<=15min)/RETWEET(<=30min)，低于此值的互动用 OneTimeWorkRequest 直接调度 */
        const val SHORT_DELAY_THRESHOLD_MS = 30L * 60L * 1000L
    }
}
