package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SchedulerInitializerWorker] 单元测试桩（#281）。
 *
 * 当前覆盖：mocked Context 下 SchedulerInitializer.initialize 失败 → Result.retry()。
 * 后续可扩展：使用 Robolectric 或 instrumentation 测试验证成功初始化路径。
 */
class SchedulerInitializerWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val params = mockk<WorkerParameters>(relaxed = true)

    @Test
    fun `mocked context 下初始化失败返回 retry`() = runTest {
        // setForeground 和 SchedulerInitializer.initialize 均会因 mocked Context 失败，
        // Worker 捕获 Throwable 后返回 retry
        val worker = SchedulerInitializerWorker(context, params)
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Retry)
    }
}
