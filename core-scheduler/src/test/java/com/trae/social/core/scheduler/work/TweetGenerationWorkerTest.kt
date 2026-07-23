package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TweetGenerationWorker] 单元测试桩（#281）。
 *
 * 当前覆盖：缺少必要输入参数时返回 failure 的输入校验路径。
 * 后续可扩展：限流跳过、429 跳过、TOCTOU 检查、成功生成等场景。
 */
class TweetGenerationWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val params = mockk<WorkerParameters>(relaxed = true)

    init {
        every { params.inputData } returns Data.EMPTY
    }

    @Test
    fun `缺少 accountId 和 deduplicationKey 时返回 failure`() = runTest {
        val worker = TweetGenerationWorker(
            context, params,
            mockk(relaxed = true), // accountRepository
            mockk(relaxed = true), // tweetRepository
            mockk(relaxed = true), // configRepository
            mockk(relaxed = true), // rulesetEngine
            mockk(relaxed = true), // gallery
            mockk(relaxed = true), // rateLimiter
            mockk(relaxed = true), // quotaChecker
            mockk(relaxed = true), // logDao
            mockk(relaxed = true), // feedbackController
            mockk(relaxed = true), // readAccess
            mockk(relaxed = true), // userActionTracker
            mockk(relaxed = true), // sessionManager
        )
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
    }
}
