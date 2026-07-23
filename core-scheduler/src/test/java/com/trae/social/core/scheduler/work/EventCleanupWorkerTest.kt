package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EventCleanupWorker] 单元测试桩（#281）。
 *
 * 当前覆盖：relaxed mock 下正常执行返回 success（TTL=0 被 coerceAtLeast(1) 兜底）。
 * 后续可扩展：指定 TTL 验证 cutoff 计算、deleteBefore 返回值验证、异常路径等。
 */
class EventCleanupWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val params = mockk<WorkerParameters>(relaxed = true)

    @Test
    fun `relaxed mock 下执行完成返回 success`() = runTest {
        // relaxed mock: getEventTtlDays()=0 → coerceAtLeast(1)=1，deleteBefore()=0
        val worker = EventCleanupWorker(
            context, params,
            mockk(relaxed = true), // userActionDao
            mockk(relaxed = true), // configRepository
            mockk(relaxed = true), // logDao
        )
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
    }
}
