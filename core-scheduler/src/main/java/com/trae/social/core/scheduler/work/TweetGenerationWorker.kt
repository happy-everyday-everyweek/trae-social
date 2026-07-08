package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import android.database.sqlite.SQLiteConstraintException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.entity.SchedulerLogEntity
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.scheduler.ratelimit.DailyQuotaChecker
import com.trae.social.core.scheduler.ratelimit.SchedulerRateLimiter
import com.trae.social.core.scheduler.rule.DeduplicationKeys
import com.trae.social.data.gallery.LocalImageGallery
import com.trae.social.data.gallery.themeToString
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.LlmProviderRegistry
import com.trae.social.llm.prompt.ContentFilter
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
 * 4. 非流式调用 [LlmProviderRegistry.getDefaultClient].chatSync；
 * 5. [TweetPromptBuilder.parseTweetResult] 失败则降级为纯文本；
 * 6. [ContentFilter.containsSensitiveContent] 命中则跳过本次；
 * 7. [TweetPostProcessor] 注入错别字 / emoji / 截断；
 * 8. withImage=true 时通过 [LocalImageGallery.pickRandom] 取配图；
 * 9. 写 [TweetEntity]（isAiGenerated=true，deduplicationKey 幂等）；
 * 10. 捕获 [SQLiteConstraintException] 静默处理；
 * 11. 写 [SchedulerLogEntity]。
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
    private val llmRegistry: LlmProviderRegistry,
    private val gallery: LocalImageGallery,
    private val rateLimiter: SchedulerRateLimiter,
    private val quotaChecker: DailyQuotaChecker,
    private val logDao: com.trae.social.core.data.dao.SchedulerLogDao,
) : CoroutineWorker(appContext, params) {

    private val promptBuilder = TweetPromptBuilder()
    private val postProcessor = TweetPostProcessor()
    private val contentFilter = ContentFilter()

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
            val level: AiActivityLevel = configRepository.getAiActivityLevel()
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

            // 限流：阻塞至令牌可用
            rateLimiter.acquire()

            // ------------------------------------------------------------------
            // 2. 查最近 3 条该账号推文
            // ------------------------------------------------------------------
            val recentTweets = tweetRepository.getByAuthor(accountId)
                .take(RECENT_TWEETS_FOR_DEDUP)
                .map { it.text }

            // ------------------------------------------------------------------
            // 3. 构建 PersonaInput，调用 TweetPromptBuilder.build()
            // ------------------------------------------------------------------
            val personaInput = TweetPromptBuilder.PersonaInput(
                displayName = account.displayName,
                profession = account.profession,
                ageRange = account.ageRange,
                culturalBackground = account.culturalBackground,
                worldview = account.worldview,
                values = account.values,
                languageStyle = account.languageStyle,
                catchphrase = account.catchphrase.joinToString("、"),
                emojiPreference = account.emojiPreference,
                typoRate = account.typoRate,
                recentMood = account.recentMood.ifBlank { "平和" },
            )
            val timeSlotDescription = describeTimeWindow(windowStart)
            val messages = promptBuilder.build(personaInput, timeSlotDescription, recentTweets)

            // ------------------------------------------------------------------
            // 4. 调用 LLM（非流式）
            // ------------------------------------------------------------------
            val rawResponse: String = try {
                llmRegistry.getDefaultClient().chatSync(
                    messages = messages,
                    config = ChatConfig(
                        temperature = 0.85f,
                        maxTokens = 320,
                        jsonMode = true,
                    ),
                )
            } catch (httpError: retrofit2.HttpException) {
                if (httpError.code() == WorkerKeys.HTTP_TOO_MANY_REQUESTS) {
                    // 429：跳过本次，不重试，避免浪费配额
                    resultStatus = "skipped_429"
                    logSchedulerEvent(accountId, started, resultStatus, httpError.message())
                    return Result.success(workDataOf(WorkerKeys.KEY_RESULT to resultStatus))
                }
                throw httpError
            }

            if (rawResponse.isBlank()) {
                Timber.w("账号 %s LLM 返回空响应", accountId)
                resultStatus = "skipped_empty_response"
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
                rawText = rawResponse.take(MAX_TWEET_LENGTH)
                withImage = false
                imageTheme = TweetPromptBuilder.ImageTheme.NONE
            }

            // ------------------------------------------------------------------
            // 6. ContentFilter 检查，敏感则跳过
            // ------------------------------------------------------------------
            if (contentFilter.containsSensitiveContent(rawText)) {
                Timber.w("账号 %s 推文命中敏感词，跳过本次", accountId)
                resultStatus = "skipped_sensitive"
                logSchedulerEvent(accountId, started, resultStatus, "sensitive content detected")
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to resultStatus))
            }

            // ------------------------------------------------------------------
            // 7. TweetPostProcessor: 错别字 + emoji + 截断
            // ------------------------------------------------------------------
            val random = Random(windowStart + accountId.hashCode())
            val withTypos = postProcessor.applyTypos(rawText, personaInput.typoRate, random)
            val withEmojis = postProcessor.appendEmojis(withTypos, personaInput.emojiPreference, random)
            val finalText = postProcessor.truncate(withEmojis, MAX_TWEET_LENGTH)

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
            Timber.e(t, "TweetGenerationWorker 执行失败 accountId=%s", accountId)
            errorMessage = t.message ?: t.javaClass.simpleName
            resultStatus = "error"
            logSchedulerEvent(accountId, started, resultStatus, errorMessage)
            // 通用异常：让 WorkManager 按退避策略重试，达到上限后自动放弃
            return if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
                Result.failure(workDataOf(WorkerKeys.KEY_ERROR to errorMessage))
            } else {
                Result.retry()
            }
        }
    }

    /**
     * 写一条调度日志。
     */
    private suspend fun logSchedulerEvent(
        accountId: String,
        startedAt: Long,
        status: String,
        error: String?,
    ) {
        runCatching {
            logDao.insert(
                SchedulerLogEntity(
                    timestamp = System.currentTimeMillis(),
                    accountId = accountId,
                    action = "tweet_generation",
                    result = status,
                    durationMs = System.currentTimeMillis() - startedAt,
                    errorMessage = error,
                )
            )
        }.onFailure { Timber.w(it, "写调度日志失败") }
    }

    /**
     * 将 windowStart（活跃窗起始时刻）转换为可读时段描述，注入 prompt。
     */
    private fun describeTimeWindow(windowStartMillis: Long): String {
        if (windowStartMillis <= 0L) return "当前时段"
        val zoned = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(windowStartMillis),
            ZoneId.systemDefault(),
        )
        val dayOfWeek = zoned.dayOfWeek
        val isWeekend = dayOfWeek == java.time.DayOfWeek.SATURDAY || dayOfWeek == java.time.DayOfWeek.SUNDAY
        val dayLabel = if (isWeekend) "周末" else "工作日"
        val startHour = zoned.hour
        val endHour = (startHour + 1).coerceAtMost(24)
        return String.format("%s %02d:00-%02d:00", dayLabel, startHour, endHour)
    }

    /**
     * 将 prompt 内部 ImageTheme 映射为 gallery 主题字符串（与 assets 目录名一致）。
     */
    private fun imageThemeToGallery(theme: TweetPromptBuilder.ImageTheme): String {
        val galleryTheme = when (theme) {
            TweetPromptBuilder.ImageTheme.LANDSCAPE -> com.trae.social.data.gallery.GalleryImageTheme.LANDSCAPE
            TweetPromptBuilder.ImageTheme.FOOD -> com.trae.social.data.gallery.GalleryImageTheme.FOOD
            TweetPromptBuilder.ImageTheme.CITY -> com.trae.social.data.gallery.GalleryImageTheme.CITY
            TweetPromptBuilder.ImageTheme.PET -> com.trae.social.data.gallery.GalleryImageTheme.PET
            TweetPromptBuilder.ImageTheme.SPORT -> com.trae.social.data.gallery.GalleryImageTheme.SPORT
            TweetPromptBuilder.ImageTheme.ART -> com.trae.social.data.gallery.GalleryImageTheme.ART
            TweetPromptBuilder.ImageTheme.TECH -> com.trae.social.data.gallery.GalleryImageTheme.TECH
            TweetPromptBuilder.ImageTheme.NATURE -> com.trae.social.data.gallery.GalleryImageTheme.NATURE
            TweetPromptBuilder.ImageTheme.NONE -> com.trae.social.data.gallery.GalleryImageTheme.NONE
        }
        return themeToString(galleryTheme)
    }

    private companion object {
        const val MAX_TWEET_LENGTH = 280
        const val RECENT_TWEETS_FOR_DEDUP = 3
        const val MAX_RUN_ATTEMPTS = 3
    }
}
