package com.trae.social.core.scheduler.work

import com.trae.social.core.data.dao.SchedulerLogDao
import com.trae.social.core.data.entity.SchedulerLogEntity
import com.trae.social.core.data.util.runCatchingCancellable
import timber.log.Timber

/**
 * #218：6 个 Worker 重复定义的 `logSchedulerEvent` / `logEvent` 共享实现。
 *
 * 此前 TweetGenerationWorker / PersonaUpdateWorker / InteractionWorker /
 * PendingInteractionWorker / UserProfileWorker / EventCleanupWorker 各自复制了
 * 几乎相同的「构造 `SchedulerLogEntity` + `runCatching { logDao.insert(...) }` +
 * `.onFailure { Timber.w(it, "写调度日志失败") }`」模板，差异仅在 `action` 字符串
 * 与函数名。任一字段变更（如增加 `traceId`、修改 `durationMs` 计算、统一失败日志格式）
 * 需改 6 处且易遗漏。
 *
 * 抽到此对象后，Worker 调用时只需传 `action` 字符串与业务字段，统一命名 `log`。
 */
object SchedulerLogger {

    /**
     * 写一条调度日志：自动填充 `timestamp = now` 与 `durationMs = now - startedAt`，
     * 失败时 `Timber.w` 记录并不抛出（日志写入失败不应影响主流程）。
     *
     * @param logDao 调度日志 DAO
     * @param action 调度动作标识，如 `"tweet_generation"` / `"interaction_schedule"` /
     *  `"pending_interaction"` / `"persona_update"` / `"user_profile"` / `"event_cleanup"`
     * @param accountId 关联账号 ID（满足 `accounts` 外键约束，无业务关联时用 `"user-self"` / `"system"`）
     * @param startedAt Worker 开始时间戳（用于计算 durationMs）
     * @param status 结果状态（如 `"success"` / `"error"` / `"retry_rate_limited"`）
     * @param error 错误信息，成功时为 null
     */
    suspend fun log(
        logDao: SchedulerLogDao,
        action: String,
        accountId: String,
        startedAt: Long,
        status: String,
        error: String?,
    ) {
        runCatchingCancellable {
            val now = System.currentTimeMillis()
            logDao.insert(
                SchedulerLogEntity(
                    timestamp = now,
                    accountId = accountId,
                    action = action,
                    result = status,
                    durationMs = now - startedAt,
                    errorMessage = error,
                )
            )
        }.onFailure { Timber.w(it, "写调度日志失败") }
    }
}
