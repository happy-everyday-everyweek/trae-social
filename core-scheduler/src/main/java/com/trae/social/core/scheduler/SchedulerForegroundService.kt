package com.trae.social.core.scheduler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.trae.social.core.scheduler.work.PendingInteractionWorker
import com.trae.social.core.scheduler.work.WorkerPolicies
import com.trae.social.core.scheduler.work.WorkerTags
import timber.log.Timber

/**
 * 调度前台服务（SubTask 8.5）。
 *
 * - foregroundServiceType=specialUse（IMPL-17：dataSync 在 Android 14+ 有 6 小时配额限制，
 *   且本服务不做数据同步，纯为保活 WorkManager，使用 specialUse 更合规）；
 * - 常驻通知"社交生态运行中"，NotificationChannel: "scheduler"，IMPORTANCE_LOW；
 * - onStartCommand 启动 PendingInteractionWorker 的周期调度（15 分钟周期）；
 * - START_STICKY 保证被杀后系统尝试重建。
 *
 * 由 [SchedulerInitializer.initialize] 或 [BootReceiver] 启动。
 */
class SchedulerForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // P0 修复：startForeground(int, Notification, int) 三参数重载自 API 29 才引入，
        // minSdk=26 时在 API 26-28 会抛 NoSuchMethodError。使用 ServiceCompat 兼容。
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            getServiceType(),
        )
        Timber.i("SchedulerForegroundService 已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 启动 PendingInteractionWorker 周期调度
        schedulePendingInteractions()
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("SchedulerForegroundService 已停止")
        super.onDestroy()
    }

    /**
     * 创建低优先级通知渠道。
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "社交生态调度器运行状态通知"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建常驻前台通知。
     */
    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 获取前台服务类型（Android 14+ 必须显式指定）。
     *
     * IMPL-17：使用 specialUse 而非 dataSync，避免 6 小时配额限制。
     */
    private fun getServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
    }

    /**
     * 调度 PendingInteractionWorker 周期任务（15 分钟周期，KEEP 策略避免重复创建）。
     */
    private fun schedulePendingInteractions() {
        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            WorkerTags.PENDING_INTERACTION,
            ExistingPeriodicWorkPolicy.KEEP,
            WorkerPolicies.pendingInteractionPeriodicRequest(),
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "scheduler"
        const val CHANNEL_NAME = "调度器"
        const val NOTIFICATION_TITLE = "社交生态运行中"
        const val NOTIFICATION_TEXT = "AI 账号正在按计划发推与互动"
    }
}
