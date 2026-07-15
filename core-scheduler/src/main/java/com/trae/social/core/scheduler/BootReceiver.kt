package com.trae.social.core.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
 * M4 修复：移除 LOCKED_BOOT_COMPLETED 与 directBootAware=true。
 * WorkManager 内部使用 credential-protected 存储，在 Direct Boot（设备未解锁）模式下
 * getInstance/enqueueUniqueWork 会抛异常。保留 LOCKED_BOOT_COMPLETED 会导致开机时崩溃。
 * 设备解锁后 BOOT_COMPLETED 仍会正常触发，满足调度恢复需求。
 *
 * RISK-3（后台调度）：保证设备重启后调度器能自动恢复运行。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED) return
        Timber.i("收到开机广播 action=%s，延迟 %d 秒触发调度初始化", action, STARTUP_DELAY_SECONDS)

        // IMPL-18：用 WorkManager 持久化延迟任务，进程被杀后仍能重放
        // #70：使用 expedited 工作请求，使 Worker 在 Android 12+ 后台上下文下
        // 也能获得前台服务启动豁免，避免 ForegroundServiceStartNotAllowedException
        // M4 修复：添加 try/catch，防止 WorkManager 未初始化等异常导致崩溃
        try {
            val request = OneTimeWorkRequestBuilder<SchedulerInitializerWorker>()
                .setInitialDelay(STARTUP_DELAY_SECONDS, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(WorkerTags.BOOT_INIT)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WorkerTags.BOOT_INIT,
                androidx.work.ExistingWorkPolicy.KEEP,
                request,
            )
        } catch (e: Exception) {
            Timber.e(e, "BootReceiver 入队调度初始化失败")
        }
    }

    private companion object {
        const val STARTUP_DELAY_SECONDS: Long = 30L
    }
}
