package com.trae.social.core.scheduler.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.workDataOf
import com.trae.social.core.data.config.AiActivityLevel
import java.util.concurrent.TimeUnit

/**
 * Worker 输入/输出键与请求构建工具集中定义，避免散落各处字符串硬编码。
 *
 * 所有键均以 `KEY_` 前缀，使用 @JvmField 便于在 Java 侧引用（虽当前为纯 Kotlin 工程）。
 */
object WorkerKeys {
    const val KEY_ACCOUNT_ID = "accountId"
    const val KEY_TWEET_ID = "tweetId"
    const val KEY_DEDUP_KEY = "deduplicationKey"
    const val KEY_WINDOW_START = "windowStart"
    const val KEY_SEQUENCE_NO = "sequenceNo"
    const val KEY_RESULT = "result"
    const val KEY_DURATION_MS = "durationMs"
    const val KEY_ERROR = "error"

    /** HTTP 429 状态码：限流时跳过重试避免浪费配额。 */
    const val HTTP_TOO_MANY_REQUESTS = 429
}

/**
 * 各 Worker 的统一标签，便于取消/查询。
 */
object WorkerTags {
    const val TWEET_GENERATION = "tweet_generation"
    const val INTERACTION = "interaction"
    const val PENDING_INTERACTION = "pending_interaction"
    const val PERSONA_UPDATE = "persona_update"
    const val USER_PROFILE = "user_profile"
    const val BOOT_INIT = "boot_init"
}

/**
 * Worker 调度策略与重试参数。
 *
 * 重试退避：指数退避，初始 10s，最大 90s，最多 3 次。
 * 429 直接 Result.success() 跳过（见各 Worker 内部判定）。
 */
object WorkerPolicies {

    val networkConstraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL
    const val BACKOFF_INITIAL_SECONDS: Long = 10L
    // #77：移除 BACKOFF_MAX_SECONDS=90L 死代码，
    // WorkManager setBackoffCriteria 不接受自定义上限，指数退避由 WM 内部按 5h 封顶

    /**
     * 构建 TweetGenerationWorker 请求。
     *
     * #89：支持可选初始延迟 [initialDelayMillis]，供自链路径复用，避免重复手动构建。
     */
    fun tweetGenerationRequest(
        accountId: String,
        deduplicationKey: String,
        windowStart: Long,
        sequenceNo: Int,
        initialDelayMillis: Long = 0L,
    ): androidx.work.OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<TweetGenerationWorker>()
            .setInputData(
                workDataOf(
                    WorkerKeys.KEY_ACCOUNT_ID to accountId,
                    WorkerKeys.KEY_DEDUP_KEY to deduplicationKey,
                    WorkerKeys.KEY_WINDOW_START to windowStart,
                    WorkerKeys.KEY_SEQUENCE_NO to sequenceNo,
                )
            )
            .setConstraints(networkConstraints)
            .setBackoffCriteria(backoffPolicy, BACKOFF_INITIAL_SECONDS, TimeUnit.SECONDS)
            .addTag(WorkerTags.TWEET_GENERATION)
        // #89：仅在有延迟时设置，避免无延迟场景产生多余调度参数
        if (initialDelayMillis > 0L) {
            builder.setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
        }
        return builder.build()
    }

    /**
     * 构建 InteractionWorker 请求。
     */
    fun interactionRequest(tweetId: String): androidx.work.OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<InteractionWorker>()
            .setInputData(workDataOf(WorkerKeys.KEY_TWEET_ID to tweetId))
            .setConstraints(networkConstraints)
            .setBackoffCriteria(backoffPolicy, BACKOFF_INITIAL_SECONDS, TimeUnit.SECONDS)
            .addTag(WorkerTags.INTERACTION)
            .build()
    }

    /**
     * 构建 PendingInteractionWorker 周期请求（15 分钟周期，最小允许值）。
     */
    fun pendingInteractionPeriodicRequest(): androidx.work.PeriodicWorkRequest {
        return PeriodicWorkRequestBuilder<PendingInteractionWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .addTag(WorkerTags.PENDING_INTERACTION)
            .build()
    }

    /**
     * 构建 PersonaUpdateWorker 周期请求。
     *
     * IMPL-47：周期按 [level] 缩放（LOW=14 天 / MEDIUM=7 天 / HIGH=3 天）。
     * m3 修复：WorkManager 周期上限 30 天可靠，将上限从 7 天放宽到 30 天，
     * 保留 LOW/MEDIUM/HIGH 的分层语义，避免 LOW 与 MEDIUM 同频导致成本翻倍。
     */
    fun personaUpdatePeriodicRequest(level: AiActivityLevel): androidx.work.PeriodicWorkRequest {
        val periodDays = level.personaUpdatePeriodDays.toLong()
        val effectivePeriodDays = periodDays.coerceIn(1L, 30L)
        return PeriodicWorkRequestBuilder<PersonaUpdateWorker>(
            effectivePeriodDays, TimeUnit.DAYS,
        )
            .setConstraints(networkConstraints)
            .setBackoffCriteria(backoffPolicy, BACKOFF_INITIAL_SECONDS, TimeUnit.SECONDS)
            .addTag(WorkerTags.PERSONA_UPDATE)
            .build()
    }

    /**
     * 构建 UserProfileWorker 周期请求（#146 第三层）。
     *
     * 周期按 [level] 缩放：LOW=96h / MEDIUM=48h / HIGH=24h。
     */
    fun userProfilePeriodicRequest(level: AiActivityLevel): androidx.work.PeriodicWorkRequest {
        val periodHours = when (level) {
            AiActivityLevel.LOW -> 96L
            AiActivityLevel.MEDIUM -> 48L
            AiActivityLevel.HIGH -> 24L
        }
        return PeriodicWorkRequestBuilder<UserProfileWorker>(
            periodHours, TimeUnit.HOURS,
        )
            .setConstraints(networkConstraints)
            .setBackoffCriteria(backoffPolicy, BACKOFF_INITIAL_SECONDS, TimeUnit.SECONDS)
            .addTag(WorkerTags.USER_PROFILE)
            .build()
    }
}
