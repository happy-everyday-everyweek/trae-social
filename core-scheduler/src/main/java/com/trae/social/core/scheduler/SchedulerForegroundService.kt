package com.trae.social.core.scheduler

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 调度前台服务桩类。
 *
 * 由 app 模块 AndroidManifest.xml 通过全限定名引用，
 * foregroundServiceType="dataSync"。具体通知与任务调度逻辑将在 Task 8 实现。
 */
class SchedulerForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // TODO(Task 8): 创建通知渠道并准备前台通知
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO(Task 8): 调用 startForeground(...) 并执行调度任务
        return START_STICKY
    }
}
