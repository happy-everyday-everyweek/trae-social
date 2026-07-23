package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UserProfileWorker] 单元测试桩（#281）。
 *
 * 当前覆盖：profiling 禁用时返回 success("profiling_disabled") 的短路路径。
 * 后续可扩展：no_snapshot 短路、fingerprint_hit 短路、LLM 调用、narrative 突变回滚等。
 */
class UserProfileWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val params = mockk<WorkerParameters>(relaxed = true)

    @Test
    fun `profiling 禁用时返回 success`() = runTest {
        // relaxed mock: isProfilingEnabled()=false → 短路返回 profiling_disabled
        val worker = UserProfileWorker(
            context, params,
            mockk(relaxed = true), // userProfileDao
            mockk(relaxed = true), // userActionDao
            mockk(relaxed = true), // feedbackDao
            mockk(relaxed = true), // aggregator
            mockk(relaxed = true), // versionStore
            mockk(relaxed = true), // generationService
            mockk(relaxed = true), // rulesetEngine
            mockk(relaxed = true), // configRepository
            mockk(relaxed = true), // logDao
        )
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
    }
}
