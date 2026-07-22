package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import android.database.sqlite.SQLiteConstraintException
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trae.social.core.data.TweetLimits
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.profiling.capture.SessionManager
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.core.profiling.feedback.FeedbackController
import com.trae.social.core.profiling.feedback.UserProfileReadAccess
import com.trae.social.core.scheduler.ratelimit.DailyQuotaChecker
import com.trae.social.core.scheduler.ratelimit.SchedulerRateLimiter
import com.trae.social.core.scheduler.rule.DeduplicationKeys
import com.trae.social.core.data.gallery.LocalImageGallery
import com.trae.social.core.data.gallery.themeToString
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.RulesetEngine
import com.trae.social.llm.interceptor.RateLimitedException
import com.trae.social.llm.prompt.TweetPostProcessor
import com.trae.social.llm.prompt.TweetPromptBuilder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.random.Random

/**
 * 推文生成 Worker（SubTask 8.2）。
 *
 * 流程：
 * 1. 读取账号人设与最近 3 条推文；
 * 2. 限流闸门：[SchedulerRateLimiter] + [DailyQuotaChecker]；
 * 3. 构建 PersonaInput 调 [TweetPromptBuilder.build]；
 * 4. 非流式调用 [RulesetEngine.chatSync]；
 * 5. [TweetPromptBuilder.parseTweetResult] 失败则降级为纯文本；
 * 6. [TweetPostProcessor] 注入错别字 / emoji / 截断；
 * 7. withImage=true 时通过 [LocalImageGallery.pickRandom] 取配图；
 * 8. 写 [TweetEntity]（isAiGenerated=true，deduplicationKey 幂等）；
 * 9. 捕获 [SQLiteConstraintException] 静默处理；
 * 10. 写 [SchedulerLogEntity]。
 *
 * 重试：BackoffPolicy.EXPONENTIAL 10s/30s/90s，最多 3 次（由 WorkManager 自动调度）。
 * 429 时返回 Result.success() 跳过本次，避免重试浪费配额。
 */
@HiltWorker
class TweetGenerationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val accountRepository: AccountRepository,
    private val tweetRepository: TweetRepository,
    private val configRepository: ConfigRepository,
    private val rulesetEngine: RulesetEngine,
    private val gallery: LocalImageGallery,
    private val rateLimiter: SchedulerRateLimiter,
    private val quotaChecker: DailyQuotaChecker,
    private val logDao: com.trae.social.core.data.dao.SchedulerLogDao,
    // #146 A/E：反哺层场景 1（topicBias）—— AI 推文主题贴近用户兴趣
    private val feedbackController: FeedbackController,
    private val readAccess: UserProfileReadAccess,
    private val userActionTracker: UserActionTracker,
    private val sessionManager: SessionManager,
) : CoroutineWorker(appContext, params) {

    private val promptBuilder = TweetPromptBuilder()
    private val postProcessor = TweetPostProcessor()

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(WorkerKeys.KEY_ACCOUNT_ID)
        val deduplicationKey = inputData.getString(WorkerKeys.KEY_DEDUP_KEY)
        val windowStart = inputData.getLong(WorkerKeys.KEY_WINDOW_START, 0L)
        if (accountId.isNullOrBlank() || deduplicationKey.isNullOrBlank()) {
            Timber.w("TweetGenerationWorker 缺少必要输入参数，accountId=%s", accountId)
            return Result.failure(
                workDataOf(WorkerKeys.KEY_ERROR to "missing required inputs")
            )
        }

        val started = System.currentTimeMillis()
        var errorMessage: String? = null
        var resultStatus = "success"

        try {
            // ------------------------------------------------------------------
            // 0. 限流闸门：配额 + 速率
            // ------------------------------------------------------------------
            // #173：与 PersonaUpdateWorker / SchedulerInitializer 保持一致，用 runCatching
            // 保护 DataStore 读取。getAiActivityLevel 内部 dataStore.data.first() 在文件损坏、
            // 并发写入冲突或首次创建时可能抛 IOException；本 Worker 是 OneTimeWorkRequest，
            // 异常会进入 catch(t: Throwable) -> Result.retry()，重试 3 次后转为 Result.failure()，
            // 由于 deduplicationKey 已被 enqueueUniqueWork KEEP 占用，会导致该活跃窗推文永久丢失。
            val level: AiActivityLevel = runCatching { configRepository.getAiActivityLevel() }
                .getOrDefault(AiActivityLevel.MEDIUM)
            rateLimiter.reconfigure(level)

            // 先查账号以获取时区（IMPL-16：配额按账号时区计算"当日"边界）
            val account = accountRepository.getById(accountId)
            if (account == null) {
                Timber.w("账号 %s 不存在，跳过推文生成", accountId)
                resultStatus = "skipped_no_account"
                logSchedulerEvent(accountId, started, resultStatus, "account not found")
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to resultStatus))
            }

            // IMPL-16：使用账号自身时区检查每日配额，避免跨时区旅行时配额边界漂移
            val accountZone = runCatching { java.time.ZoneId.of(account.timezone) }
                .getOrElse { java.time.ZoneId.systemDefault() }
            if (quotaChecker.isQuotaExhausted(accountId, level, zone = accountZone)) {
                Timber.i("账号 %s 当日配额已耗尽，跳过推文生成", accountId)
                resultStatus = "skipped_quota"
                logSchedulerEvent(accountId, started, resultStatus, null)
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to resultStatus))
            }

            // 限流：M2 修复——使用带超时的 acquire，避免限流阻塞超过 WorkManager 超时上限
            if (!rateLimiter.acquireWithTimeout(WorkerConstants.ACQUIRE_TIMEOUT_MS)) {
                Timber.i("账号 %s 限流等待超时，稍后重试", accountId)
                resultStatus = "retry_rate_limited"
                logSchedulerEvent(accountId, started, resultStatus, "acquire timeout")
                return Result.retry()
            }

            // ------------------------------------------------------------------
            // 2. 查最近 3 条该账号推文
            // ------------------------------------------------------------------
            // 主 review 第 4 轮修复：原 getByAuthor(accountId).take(N) 会先把账号全部推文加载到
            // 内存再截断，虚拟账号推文多时存在不必要的内存与 IO 开销。改用 getByAuthorLimit
            // 在 SQL 层 LIMIT N，与本文件已新增的 #177 查询对齐。
            val recentTweets = tweetRepository.getByAuthorLimit(accountId, RECENT_TWEETS_FOR_DEDUP)
                .map { it.text }

            // #146 A/E 场景 1（topicBias）：判断本次是否 driven（画像驱动推文主题）。
            // driven 组把用户兴趣向量 top 主题作为"近期关注话题"提示注入 prompt 上下文，
            // 引导 AI 账号产出更贴近用户兴趣的内容；control 组不注入，供 computeFeedbackEffect 回测。
            val sessionId = sessionManager.currentSessionId() ?: accountId
            val drivenScenario1 = feedbackController.shouldApply(1, sessionId)
            val promptContext = if (drivenScenario1) {
                val topInterests = readAccess.interestVector()
                    .entries.sortedByDescending { it.value }.take(5).joinToString("、") { it.key }
                if (topInterests.isNotBlank()) {
                    // 作为额外上下文条目插入 recentTweets 前部，buildUserPrompt 会将其作为近期上下文呈现；
                    // 不影响去重（去重是精确文本匹配，提示串不会匹配真实推文）
                    listOf("【用户近期关注话题】$topInterests") + recentTweets
                } else {
                    recentTweets
                }
            } else {
                recentTweets
            }

            // ------------------------------------------------------------------
            // 3. 构建 PersonaInput，调用 TweetPromptBuilder.build()
            // #219：统一走 PersonaInput.from(account)，与 InteractionWorker /
            // PendingInteractionWorker 复用同一映射，避免字段调整时多处修改
            // ------------------------------------------------------------------
            val personaInput = TweetPromptBuilder.PersonaInput.from(account)
            // #80：传入 activeWindows 以计算活跃窗实际结束小时
            val timeSlotDescription = describeTimeWindow(windowStart, accountZone, account.activeWindows)
            val messages = promptBuilder.build(personaInput, timeSlotDescription, promptContext)

            // ------------------------------------------------------------------
            // 4. 调用 LLM（非流式）
            // ------------------------------------------------------------------
            val rawResponse: String = try {
                rulesetEngine.chatSync(
                    messages = messages,
                    config = ChatConfig(
                        temperature = 0.85f,
                        maxTokens = 320,
                        jsonMode = true,
                    ),
                )
            } catch (e: RateLimitedException) {
                // #151 重构后：DefaultRulesetEngine 把 SDK 的 429 异常转换为 RateLimitedException，
                // 各 Worker 既有 catch 分支继续生效，跳过重试避免浪费配额。
                resultStatus = "skipped_429"
                logSchedulerEvent(accountId, started, resultStatus, e.message)
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to resultStatus))
            }

            if (rawResponse.isBlank()) {
                Timber.w("账号 %s LLM 返回空响应", accountId)
                // #94：状态改为 retry_empty_response 以准确反映重试行为
                resultStatus = "retry_empty_response"
                logSchedulerEvent(accountId, started, resultStatus, "empty LLM response")
                return Result.retry()
            }

            // ------------------------------------------------------------------
            // 5. parseTweetResult，失败降级为纯文本
            // ------------------------------------------------------------------
            val parsed = TweetPromptBuilder.parseTweetResult(rawResponse)
            val rawText: String
            val withImage: Boolean
            val imageTheme: TweetPromptBuilder.ImageTheme

            if (parsed != null) {
                rawText = parsed.text
                withImage = parsed.withImage
                imageTheme = parsed.imageTheme
            } else {
                Timber.w("账号 %s 推文 JSON 解析失败，降级为纯文本", accountId)
                rawText = rawResponse.take(TweetLimits.MAX_TWEET_LENGTH)
                withImage = false
                imageTheme = TweetPromptBuilder.ImageTheme.NONE
            }

            // ------------------------------------------------------------------
            // 6. TweetPostProcessor: 错别字 + emoji + 截断
            // (#151 重构移除 ContentFilter，敏感词检查下沉到模型层 / 上层审核流程)
            // ------------------------------------------------------------------
            val random = Random(windowStart + accountId.hashCode())
            val withTypos = postProcessor.applyTypos(rawText, personaInput.typoRate, random)
            val withEmojis = postProcessor.appendEmojis(withTypos, personaInput.emojiPreference, random)
            val finalText = postProcessor.truncate(withEmojis, TweetLimits.MAX_TWEET_LENGTH)

            // ------------------------------------------------------------------
            // 8. 配图选取
            // ------------------------------------------------------------------
            var mediaPath: String? = null
            var mediaTheme: String? = null
            if (withImage && imageTheme != TweetPromptBuilder.ImageTheme.NONE) {
                val themeStr = imageThemeToGallery(imageTheme)
                mediaPath = gallery.pickRandom(themeStr, accountId)
                if (mediaPath != null) {
                    mediaTheme = themeStr
                }
            }

            // ------------------------------------------------------------------
            // 8.5 #117：TOCTOU 检查——执行时再次校验窗内推文数
            // 调度时检查与执行时写入之间存在时间窗口，并发或补发可能使窗内推文数超限。
            // M3 修复：使用活跃窗实际结束时刻，而非硬编码 1 小时，避免多小时窗口超发
            // ------------------------------------------------------------------
            if (windowStart > 0L) {
                val windowEndMillis = com.trae.social.core.scheduler.rule.ScheduleRuleResolver
                    .windowEndMillisForStart(windowStart, accountZone, account.activeWindows)
                val currentInWindow = runCatching {
                    tweetRepository.countByAuthorInWindow(accountId, windowStart, windowEndMillis)
                }.getOrDefault(0)
                if (currentInWindow >= POSTS_PER_WINDOW) {
                    Timber.i("账号 %s 窗内推文数已达上限 %d/%d，跳过（TOCTOU）",
                        accountId, currentInWindow, POSTS_PER_WINDOW)
                    resultStatus = "skipped_window_full"
                    logSchedulerEvent(accountId, started, resultStatus, "window full at execution time")
                    return Result.success(workDataOf(WorkerKeys.KEY_RESULT to resultStatus))
                }
            }

            // ------------------------------------------------------------------
            // 9. 构建 TweetEntity 并写入
            // ------------------------------------------------------------------
            val now = System.currentTimeMillis()
            val tweet = TweetEntity(
                id = UUID.randomUUID().toString(),
                authorId = accountId,
                text = finalText,
                mediaPath = mediaPath,
                mediaTheme = mediaTheme,
                createdAt = now,
                likeCount = 0,
                commentCount = 0,
                retweetCount = 0,
                isAiGenerated = true,
                deduplicationKey = deduplicationKey,
            )

            try {
                tweetRepository.insertTweet(tweet)
            } catch (constraint: SQLiteConstraintException) {
                // 10. 幂等：deduplicationKey 唯一约束冲突时静默处理
                Timber.i("账号 %s 推文去重键冲突，视为已完成: %s", accountId, deduplicationKey)
                resultStatus = "skipped_duplicate"
                logSchedulerEvent(accountId, started, resultStatus, "deduplication conflict")
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to resultStatus))
            }

            // #146 A：反哺层打标——为本次推文生成发 scenario 1 事件，供 computeFeedbackEffect 做 A/B 回测。
            // drivenByProfile 标记本次生成是否受画像驱动（注入了用户兴趣话题）；
            // control 组同样落事件以便后续计算 driven/control 两组的内容触达率与互动率 delta。
            // 第六轮 review B1/B2 修复：isScenarioMarker=true 标记本事件为调度器打标（非真实用户行为），
            // 供 UserProfileAggregator.computeScenarioStats 区分"曝光标记"与"真实互动"，
            // 供 BasicProfileAnalyzer.analyze 过滤掉调度器打标，避免污染用户画像。
            runCatching {
                userActionTracker.trackNow(
                    UserActionEvent(
                        // 第七轮 review M6 修复：用稳定 id 替代 UUID.randomUUID()。
                        // Worker 重试时 deduplicationKey 不变（来自 inputData），故同一生成槽位
                        // 重试产生相同 id，Room @PrimaryKey + REPLACE 策略保证幂等（不产生重复 marker），
                        // 避免 A/B 曝光计数被重试膨胀。
                        id = "marker_s1_$deduplicationKey",
                        type = UserActionType.PUBLISH_TWEET,
                        screen = "tweet_generation",
                        targetId = tweet.id,
                        targetKind = "tweet",
                        extra = mapOf(
                            "scenarioId" to kotlinx.serialization.json.JsonPrimitive(1),
                            "drivenByProfile" to kotlinx.serialization.json.JsonPrimitive(drivenScenario1),
                            "group" to kotlinx.serialization.json.JsonPrimitive(if (drivenScenario1) "driven" else "control"),
                            "authorId" to kotlinx.serialization.json.JsonPrimitive(accountId),
                            "isScenarioMarker" to kotlinx.serialization.json.JsonPrimitive(true),
                        ),
                        occurredAt = now,
                        session = sessionId,
                    )
                )
            }.onFailure { Timber.w(it, "#146 场景 1 打标失败") }

            // P1 修复：AI 推文入库后触发 InteractionWorker 排程互动，
            // 使 AI 生成内容也能获得点赞/评论/转发，避免信息流"死气沉沉"。
            // #89：复用 WorkerPolicies.interactionRequest 替代手动构建
            runCatching {
                val interactionRequest = WorkerPolicies.interactionRequest(tweet.id)
                // P2 修复：使用 enqueueUniqueWork 避免 InteractionWorker 重复入队
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    "interaction_${tweet.id}",
                    androidx.work.ExistingWorkPolicy.KEEP,
                    interactionRequest,
                )
            }.onFailure { Timber.w(it, "入队 InteractionWorker 失败") }

            // ------------------------------------------------------------------
            // P1 修复：自链——成功生成推文后，入队下一个延迟 TweetGenerationWorker，
            // 使连续调度链不中断。SchedulerInitializer.initialize() 只在每个进程首次启动
            // 时入队每个账号的首个 Worker；后续触发由 Worker 自身链式入队完成。
            // ------------------------------------------------------------------
            runCatching {
                scheduleNextTweetGeneration(accountId, accountZone)
            }.onFailure { Timber.w(it, "入队下一个 TweetGenerationWorker 失败") }

            // ------------------------------------------------------------------
            // 11. 写 SchedulerLogEntity
            // ------------------------------------------------------------------
            resultStatus = "success"
            logSchedulerEvent(accountId, started, resultStatus, null)
            return Result.success(
                workDataOf(
                    WorkerKeys.KEY_RESULT to resultStatus,
                    WorkerKeys.KEY_TWEET_ID to tweet.id,
                )
            )
        } catch (t: Throwable) {
            // 第六轮 review M3 修复：CancellationException 必须重抛，否则 WorkManager 取消 Worker 时
            // 协程无法正确传播取消信号，导致 doWork 卡在 catch(t: Throwable) 内继续执行返回 Result.retry。
            if (t is kotlinx.coroutines.CancellationException) throw t
            Timber.e(t, "TweetGenerationWorker 执行失败 accountId=%s", accountId)
            errorMessage = t.message ?: t.javaClass.simpleName
            resultStatus = "error"
            logSchedulerEvent(accountId, started, resultStatus, errorMessage)
            // 通用异常：让 WorkManager 按退避策略重试，达到上限后自动放弃
            return if (runAttemptCount >= WorkerConstants.MAX_RUN_ATTEMPTS) {
                Result.failure(workDataOf(WorkerKeys.KEY_ERROR to errorMessage))
            } else {
                Result.retry()
            }
        }
    }

    /**
     * 写一条调度日志。
     *
     * #218：实现抽到 [SchedulerLogger.log]，此处保留薄包装仅供本 Worker 内部调用，
     * 统一注入 action = `"tweet_generation"`。
     */
    private suspend fun logSchedulerEvent(
        accountId: String,
        startedAt: Long,
        status: String,
        error: String?,
    ) {
        SchedulerLogger.log(logDao, "tweet_generation", accountId, startedAt, status, error)
    }

    /**
     * P1 修复：自链调度——当前 Worker 成功完成后，入队该账号的下一个延迟 TweetGenerationWorker。
     *
     * 通过 [ScheduleRuleResolver.nextTriggerTime] 计算下一次触发时刻，并用
     * [TweetRepository.countByAuthorInWindow] 检查窗内已发布数是否已达上限。
     * 计算失败或无下一窗时静默跳过（不影响本次成功结果）。
     *
     * #302 修复：sequenceNo 此前恒为 0，导致 postsPerWindow > 1 时同一窗口内第 2 条
     * 及以后推文的 deduplicationKey 与第 1 条相同，被 enqueueUniqueWork(KEEP) 丢弃，
     * AI 账号每窗实际只能发 1 条（发布量被腰斩）。改为按目标窗内已发布数计算 sequenceNo，
     * 保证同一窗口内多条推文的 deduplicationKey 互异。
     */
    private suspend fun scheduleNextTweetGeneration(accountId: String, accountZone: ZoneId) {
        val account = accountRepository.getById(accountId) ?: return
        val level = runCatching { configRepository.getAiActivityLevel() }
            .getOrDefault(AiActivityLevel.MEDIUM)
        val rule = com.trae.social.core.scheduler.rule.ScheduleRule(
            accountId = accountId,
            activeWindows = account.activeWindows,
            postsPerWindow = POSTS_PER_WINDOW,
        )
        val now = java.time.Instant.now()
        val nextTrigger = com.trae.social.core.scheduler.rule.ScheduleRuleResolver.nextTriggerTime(
            rule = rule,
            now = now,
            zone = accountZone,
            postsInWindowProvider = { aid, ws, we ->
                tweetRepository.countByAuthorInWindow(aid, ws, we)
            },
        ) ?: return

        // #109：自链路径使用窗口起始时刻（而非随机触发时刻）作为 dedup key 的 windowStart，
        // 与补发路径保持一致，确保跨重启幂等性。
        // M3 修复：传入 activeWindows 以查找实际窗口起点，避免多小时窗口 dedup key 不一致
        val currentWindowStart = com.trae.social.core.scheduler.rule.ScheduleRuleResolver
            .windowStartForTrigger(nextTrigger, accountZone, account.activeWindows) ?: nextTrigger
        val windowStartMillis = currentWindowStart.toEpochMilli()
        // #302：按目标窗内已发布数计算 sequenceNo，使同窗多条推文 dedup key 互异。
        // nextTriggerTime 已基于 postsInWindowProvider 跳过满窗，故此处 existingInWindow < POSTS_PER_WINDOW。
        val windowEndMillis = com.trae.social.core.scheduler.rule.ScheduleRuleResolver
            .windowEndMillisForStart(windowStartMillis, accountZone, account.activeWindows)
        val sequenceNo = runCatching {
            tweetRepository.countByAuthorInWindow(accountId, windowStartMillis, windowEndMillis)
        }.getOrDefault(0).coerceAtLeast(0)
        // 双重守卫：若窗已满（nextTriggerTime 与此处查询之间存在 TOCTOU 间隙），跳过本次入队
        if (sequenceNo >= POSTS_PER_WINDOW) {
            Timber.i("账号 %s 目标窗内已发 %d 条，跳过自链入队", accountId, sequenceNo)
            return
        }
        val deduplicationKey = com.trae.social.core.scheduler.rule.DeduplicationKeys.forTweet(
            accountId = accountId,
            windowStart = windowStartMillis,
            sequenceNo = sequenceNo,
        )
        val delayMillis = (nextTrigger.toEpochMilli() - System.currentTimeMillis())
            .coerceAtLeast(0L)
        // #89：复用 WorkerPolicies.tweetGenerationRequest 替代手动构建
        val request = WorkerPolicies.tweetGenerationRequest(
            accountId = accountId,
            deduplicationKey = deduplicationKey,
            windowStart = windowStartMillis,
            sequenceNo = sequenceNo,
            initialDelayMillis = delayMillis,
        )
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            deduplicationKey,
            androidx.work.ExistingWorkPolicy.KEEP,
            request,
        )
        Timber.i(
            "账号 %s 已自链入队下一个 TweetGenerationWorker，触发时刻: %s，sequenceNo: %d",
            accountId,
            nextTrigger,
            sequenceNo,
        )
    }

    /**
     * 将 windowStart（活跃窗起始时刻）转换为可读时段描述，注入 prompt。
     *
     * P1 修复：使用账号自身时区 [zone] 而非系统时区，避免跨时区旅行时
     * prompt 中的时段描述与账号实际活跃窗不符。
     *
     * #80：根据 [activeWindows] 解析出窗口实际结束小时，而非固定假设 1 小时窗口。
     */
    private fun describeTimeWindow(
        windowStartMillis: Long,
        zone: ZoneId,
        activeWindows: List<Boolean>,
    ): String {
        if (windowStartMillis <= 0L) return "当前时段"
        val zoned = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(windowStartMillis),
            zone,
        )
        val dayOfWeek = zoned.dayOfWeek
        val isWeekend = dayOfWeek == java.time.DayOfWeek.SATURDAY || dayOfWeek == java.time.DayOfWeek.SUNDAY
        val dayLabel = if (isWeekend) "周末" else "工作日"
        val startHour = zoned.hour
        // #80：从活跃窗配置解析实际结束小时，避免仅显示首个小时
        val windows = com.trae.social.core.scheduler.rule.ScheduleRuleResolver.parseWindows(activeWindows)
        val matchingWindow = windows.firstOrNull { it.contains(startHour) }
        val endHour = matchingWindow?.endHour ?: (startHour + 1).coerceAtMost(24)
        return String.format("%s %02d:00-%02d:00", dayLabel, startHour, endHour)
    }

    /**
     * 将 prompt 内部 ImageTheme 映射为 gallery 主题字符串（与 assets 目录名一致）。
     */
    private fun imageThemeToGallery(theme: TweetPromptBuilder.ImageTheme): String {
        val galleryTheme = when (theme) {
            TweetPromptBuilder.ImageTheme.LANDSCAPE -> com.trae.social.core.data.gallery.GalleryImageTheme.LANDSCAPE
            TweetPromptBuilder.ImageTheme.FOOD -> com.trae.social.core.data.gallery.GalleryImageTheme.FOOD
            TweetPromptBuilder.ImageTheme.CITY -> com.trae.social.core.data.gallery.GalleryImageTheme.CITY
            TweetPromptBuilder.ImageTheme.PET -> com.trae.social.core.data.gallery.GalleryImageTheme.PET
            TweetPromptBuilder.ImageTheme.SPORT -> com.trae.social.core.data.gallery.GalleryImageTheme.SPORT
            TweetPromptBuilder.ImageTheme.ART -> com.trae.social.core.data.gallery.GalleryImageTheme.ART
            TweetPromptBuilder.ImageTheme.TECH -> com.trae.social.core.data.gallery.GalleryImageTheme.TECH
            TweetPromptBuilder.ImageTheme.NATURE -> com.trae.social.core.data.gallery.GalleryImageTheme.NATURE
            TweetPromptBuilder.ImageTheme.NONE -> com.trae.social.core.data.gallery.GalleryImageTheme.NONE
        }
        return themeToString(galleryTheme)
    }

    private companion object {
        const val RECENT_TWEETS_FOR_DEDUP = 3

        /** P1 修复：每个活跃窗内允许发布的推文数上限（与 SchedulerInitializer 保持一致） */
        const val POSTS_PER_WINDOW = 2
    }
}
