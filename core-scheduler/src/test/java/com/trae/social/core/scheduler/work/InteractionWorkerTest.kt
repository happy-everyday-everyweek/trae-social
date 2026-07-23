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
 * [InteractionWorker] 单元测试桩（#281）。
 *
 * 当前覆盖：缺少 tweetId 时返回 failure 的输入校验路径。
 * 后续可扩展：互动分配、评论生成、429 限流跳过等场景。
 */
class InteractionWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val params = mockk<WorkerParameters>(relaxed = true)

    init {
        every { params.inputData } returns Data.EMPTY
    }

    @Test
    fun `缺少 tweetId 时返回 failure`() = runTest {
        val worker = InteractionWorker(
            context, params,
            mockk(relaxed = true), // accountRepository
            mockk(relaxed = true), // tweetRepository
            mockk(relaxed = true), // interactionRepository
            mockk(relaxed = true), // rulesetEngine
            mockk(relaxed = true), // rateLimiter
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
