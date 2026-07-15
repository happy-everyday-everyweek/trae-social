package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.entity.SchedulerLogEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * 用户行为事件清理 Worker（#146 G 修复：补全 eventTtlDays 清理逻辑）。
 *
 * 周期：24h。读取 [ConfigRepository.getEventTtlDays]（默认 14 天），
 * 删除 [UserActionDao.deleteBefore] 早于 cutoff 的原始事件，防止 user_action_events 无限增长。
 *
 * 保留窗口与 [BasicProfileAnalyzer] 的 14 天分析窗口协调（TTL >= 分析窗口，避免删掉仍在用的数据）。
 */
@HiltWorker
class EventCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val userActionDao: UserActionDao,
    private val configRepository: ConfigRepository,
    private val logDao: com.trae.social.core.data.dao.SchedulerLogDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        return try {
            val ttlDays = configRepository.getEventTtlDays()
            // TTL 至少保留 1 天，避免误删近期数据
            val safeTtlDays = ttlDays.coerceAtLeast(1)
            val cutoff = started - safeTtlDays.toLong() * 24 * 60 * 60 * 1000L
            val deleted = userActionDao.deleteBefore(cutoff)
            Timber.i("EventCleanupWorker 清理 %d 条过期事件（TTL=%d 天，cutoff=%d）", deleted, safeTtlDays, cutoff)
            logEvent(started, "success", "deleted=$deleted")
            Result.success(workDataOf(WorkerKeys.KEY_RESULT to "success", "deleted" to deleted))
        } catch (t: Throwable) {
            Timber.e(t, "EventCleanupWorker 执行失败")
            logEvent(started, "error", t.message)
            // 清理失败不阻塞业务，下次周期重试即可
            Result.success(workDataOf(WorkerKeys.KEY_RESULT to "error"))
        }
    }

    private suspend fun logEvent(startedAt: Long, status: String, error: String?) {
        runCatching {
            logDao.insert(
                SchedulerLogEntity(
                    timestamp = System.currentTimeMillis(),
                    accountId = LOG_ACCOUNT_ID,
                    action = "event_cleanup",
                    result = status,
                    durationMs = System.currentTimeMillis() - startedAt,
                    errorMessage = error,
                )
            )
        }.onFailure { Timber.w(it, "写清理日志失败") }
    }

    private companion object {
        // 与 UserProfileWorker 一致：日志 accountId 用 user-self（真实账号，满足外键约束）
        const val LOG_ACCOUNT_ID = "user-self"
    }
}
