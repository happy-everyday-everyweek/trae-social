package com.trae.social.core.scheduler.work

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.trae.social.core.data.util.runCatchingCancellable
import com.trae.social.core.scheduler.SchedulerInitializer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * 开机后延迟初始化调度器的 Worker（IMPL-18）。
 *
 * 由 [com.trae.social.core.scheduler.BootReceiver] 通过 OneTimeWorkRequest + setInitialDelay
 * 入队，进程被杀后 WorkManager 仍能重放，比 Handler.postDelayed 更可靠。
 *
 * #70：开机后应用处于后台，Android 12+ 直接启动前台服务会抛
 * ForegroundServiceStartNotAllowedException。通过 setForeground 将 Worker 提升为前台，
 * 并在 BootReceiver 中以 expedited 方式入队，使调度器初始化能可靠完成。
 */
@HiltWorker
class SchedulerInitializerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // #70：先将 Worker 提升为前台，使其获得前台服务启动豁免
        runCatchingCancellable { setForeground(createForegroundInfo()) }
            .onFailure { Timber.w(it, "setForeground 失败，继续尝试初始化") }

        return try {
            SchedulerInitializer.initialize(applicationContext)
            Result.success()
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // #70：后台上下文仍无法启动前台服务，稍后重试
            Timber.w(e, "开机后无法启动前台服务，稍后重试")
            Result.retry()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.e(t, "SchedulerInitializerWorker 执行失败")
            Result.retry()
        }
    }

    /**
     * #70：构建前台通知，复用 SchedulerForegroundService 的通知渠道。
     */
    private fun createForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText("调度器启动中")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "社交生态运行状态通知"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private companion object {
        // 复用 SchedulerForegroundService 的渠道，避免重复创建
        const val CHANNEL_ID = "scheduler"
        const val CHANNEL_NAME = "社交动态"
        const val NOTIFICATION_TITLE = "Trae Social 运行中"
        // 与 SchedulerForegroundService 的 1001 区分，避免通知覆盖冲突
        const val NOTIFICATION_ID = 1002
    }
}
