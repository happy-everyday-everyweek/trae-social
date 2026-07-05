package com.trae.social.core.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * 开机自启接收器（SubTask 8.5）。
 *
 * 开机完成后延迟 30 秒启动 [SchedulerForegroundService]，避免开机瞬时资源争抢。
 *
 * RISK-3（后台调度）：保证设备重启后调度器能自动恢复运行。
 *
 * 注意：Android 10+ 对后台启动服务有限制，但接收 BOOT_COMPLETED 广播时
 * 应用处于"允许启动活动"的窗口期，可正常 startForegroundService。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Timber.i("收到开机广播，延迟 %d 秒启动调度服务", STARTUP_DELAY_SECONDS)
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            startSchedulerService(context)
        }, STARTUP_DELAY_MILLIS)
    }

    /**
     * 启动调度前台服务。
     */
    private fun startSchedulerService(context: Context) {
        runCatching {
            val serviceIntent = Intent(context, SchedulerForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }.onFailure { Timber.w(it, "启动调度服务失败") }
    }

    private companion object {
        const val STARTUP_DELAY_SECONDS: Long = 30L
        const val STARTUP_DELAY_MILLIS: Long = STARTUP_DELAY_SECONDS * 1000L
    }
}
