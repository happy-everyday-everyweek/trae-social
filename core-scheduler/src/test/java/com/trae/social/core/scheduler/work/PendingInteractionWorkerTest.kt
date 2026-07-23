package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PendingInteractionWorker] 单元测试桩（#281）。
 *
 * 当前覆盖：无待执行互动时返回 success("no_pending")。
 * 后续可扩展：COMMENT 评论文本生成、互动计数累加、429 限流处理等。
 */
class PendingInteractionWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val params = mockk<WorkerParameters>(relaxed = true)

    @Test
    fun `无待执行互动时返回 success`() = runTest {
        // relaxed mock 的 interactionRepository.getPendingInteractions 返回空列表
        val worker = PendingInteractionWorker(
            context, params,
            mockk(relaxed = true), // interactionRepository
            mockk(relaxed = true), // tweetRepository
            mockk(relaxed = true), // accountRepository
            mockk(relaxed = true), // rulesetEngine
            mockk(relaxed = true), // rateLimiter
            mockk(relaxed = true), // logDao
        )
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
    }
}
