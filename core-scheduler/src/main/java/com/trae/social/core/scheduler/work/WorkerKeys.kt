package com.trae.social.core.scheduler.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.workDataOf
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
    const val BACKOFF_MAX_SECONDS: Long = 90L

    /**
     * 构建 TweetGenerationWorker 请求。
     */
    fun tweetGenerationRequest(
        accountId: String,
        deduplicationKey: String,
        windowStart: Long,
        sequenceNo: Int,
    ): androidx.work.OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<TweetGenerationWorker>()
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
            .build()
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
     * 构建 PersonaUpdateWorker 周期请求（7 天周期）。
     */
    fun personaUpdatePeriodicRequest(): androidx.work.PeriodicWorkRequest {
        return PeriodicWorkRequestBuilder<PersonaUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(networkConstraints)
            .addTag(WorkerTags.PERSONA_UPDATE)
            .build()
    }
}
