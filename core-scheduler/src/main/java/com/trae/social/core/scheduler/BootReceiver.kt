package com.trae.social.core.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.trae.social.core.scheduler.work.SchedulerInitializerWorker
import com.trae.social.core.scheduler.work.WorkerTags
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 开机自启接收器（SubTask 8.5）。
 *
 * 开机完成后通过 OneTimeWorkRequest + setInitialDelay 延迟 30 秒触发调度初始化，
 * 避免开机瞬时资源争抢。
 *
 * IMPL-18：不再用 Handler.postDelayed——进程可能在 30 秒回调前被杀，
 * 导致调度器无法启动。改用 WorkManager 持久化延迟任务，进程重启后仍能触发。
 *
 * 同时监听 LOCKED_BOOT_COMPLETED（Direct Boot 模式），使设备解锁前也能唤醒调度。
 *
 * RISK-3（后台调度）：保证设备重启后调度器能自动恢复运行。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        Timber.i("收到开机广播 action=%s，延迟 %d 秒触发调度初始化", action, STARTUP_DELAY_SECONDS)

        // IMPL-18：用 WorkManager 持久化延迟任务，进程被杀后仍能重放
        val request = OneTimeWorkRequestBuilder<SchedulerInitializerWorker>()
            .setInitialDelay(STARTUP_DELAY_SECONDS, TimeUnit.SECONDS)
            .addTag(WorkerTags.BOOT_INIT)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WorkerTags.BOOT_INIT,
            androidx.work.ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val STARTUP_DELAY_SECONDS: Long = 30L
    }
}
