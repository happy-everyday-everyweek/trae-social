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
 * Worker 跨模块共享的运行期约束常量。
 *
 * 历史上 `MAX_RUN_ATTEMPTS` / `ACQUIRE_TIMEOUT_MS` / `MAX_COMMENT_LENGTH` 在每个
 * Worker 的 companion object 中重复定义，任一处修改未同步会导致配置漂移
 * （典型风险：超时配置漂移会让部分 Worker 被 WorkManager 强杀）。
 * 统一在此声明，各 Worker 引用之；业务专属常量仍保留在各自 companion object。
 */
object WorkerConstants {
    /**
     * Worker 最大重试次数。超过后转为 Result.failure()。
     *
     * 与 [WorkerPolicies.backoffPolicy] 配合：指数退避初始 10s，3 次封顶。
     */
    const val MAX_RUN_ATTEMPTS = 3

    /**
     * 限流等待超时（8 分钟），低于 WorkManager 默认 10 分钟超时。
     *
     * 避免 SchedulerRateLimiter 长时间阻塞导致 Worker 被 WM 强杀。
     */
    const val ACQUIRE_TIMEOUT_MS = 8L * 60L * 1000L

    /**
     * AI 生成评论的字数上限。
     */
    const val MAX_COMMENT_LENGTH = 100
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
    /** #146 G 修复：用户行为事件清理（按 TTL 删除过期原始事件） */
    const val EVENT_CLEANUP = "event_cleanup"
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
     * IMPL-47：周期按 [level] 缩放（LOW=7 天 / MEDIUM=3 天 / HIGH=3 天）。
     * #95：原 LOW=14 / MEDIUM=7 / HIGH=3 已在 [AiActivityLevel.personaUpdatePeriodDays]
     * 中缩短，避免 Doze 模式下大幅推迟导致人设演进近乎停滞。
     * 同时通过 [setInitialDelay] 锚定首执行到下一个凌晨低峰期（3 点本地时间），
     * 减少白天高峰期的额外调度压力；周期上限保持 30 天以兼容 LOW 档。
     * 注：PeriodicWorkRequest 不支持 setExpedited（WM 会抛 IllegalStateException），
     * 故仅通过 setInitialDelay + 缩短周期 + 前台服务保障（#70）三项组合缓解 Doze 时延。
     */
    fun personaUpdatePeriodicRequest(level: AiActivityLevel): androidx.work.PeriodicWorkRequest {
        val periodDays = level.personaUpdatePeriodDays.toLong()
        val effectivePeriodDays = periodDays.coerceIn(1L, 30L)
        // #95：锚定首执行到下一个本地凌晨 3 点，避开白天用户活跃高峰
        val initialDelayMillis = computeInitialDelayToNextHour(3)
        return PeriodicWorkRequestBuilder<PersonaUpdateWorker>(
            effectivePeriodDays, TimeUnit.DAYS,
        )
            .setConstraints(networkConstraints)
            .setBackoffCriteria(backoffPolicy, BACKOFF_INITIAL_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag(WorkerTags.PERSONA_UPDATE)
            .build()
    }

    /**
     * #95：计算到下一个目标小时（本地时间）的延迟毫秒数。
     *
     * 用于将周期任务的首次执行锚定到低峰时段（如凌晨 3 点），减少 Doze 推迟的随机性。
     */
    private fun computeInitialDelayToNextHour(targetHour: Int): Long {
        val now = java.util.Calendar.getInstance()
        val target = (now.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, targetHour)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            // 若今日目标时刻已过，则锚定到明日同时刻
            if (timeInMillis <= now.timeInMillis) {
                add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
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

    /**
     * 构建 EventCleanupWorker 周期请求（#146 G 修复：24h 周期清理过期用户行为事件）。
     *
     * 无网络约束（纯本地 DB 清理，离线可执行）。
     */
    fun eventCleanupPeriodicRequest(): androidx.work.PeriodicWorkRequest {
        return PeriodicWorkRequestBuilder<EventCleanupWorker>(24, TimeUnit.HOURS)
            .setBackoffCriteria(backoffPolicy, BACKOFF_INITIAL_SECONDS, TimeUnit.SECONDS)
            .addTag(WorkerTags.EVENT_CLEANUP)
            .build()
    }
}
