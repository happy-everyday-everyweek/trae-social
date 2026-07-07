package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.trae.social.core.scheduler.SchedulerInitializer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * 开机后延迟初始化调度器的 Worker（IMPL-18）。
 *
 * 由 [com.trae.social.core.scheduler.BootReceiver] 通过 OneTimeWorkRequest + setInitialDelay
 * 入队，进程被杀后 WorkManager 仍能重放，比 Handler.postDelayed 更可靠。
 */
@HiltWorker
class SchedulerInitializerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            SchedulerInitializer.initialize(applicationContext)
            Result.success()
        }.getOrElse { t ->
            Timber.e(t, "SchedulerInitializerWorker 执行失败")
            Result.retry()
        }
    }
}
