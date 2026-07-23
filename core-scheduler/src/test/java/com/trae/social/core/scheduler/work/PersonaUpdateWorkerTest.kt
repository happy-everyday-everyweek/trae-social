package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PersonaUpdateWorker] 单元测试桩（#281）。
 *
 * 当前覆盖：relaxed mock 下无候选账号时返回 success("no_accounts")。
 * 后续可扩展：单账号人设更新、429 限流跳过整批、narrative 回滚等。
 */
class PersonaUpdateWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val params = mockk<WorkerParameters>(relaxed = true)

    @Test
    fun `无候选账号时返回 success`() = runTest {
        // relaxed mock: accountRepository 返回空列表 → pickRandomAccounts 为空 → no_accounts
        val worker = PersonaUpdateWorker(
            context, params,
            mockk(relaxed = true), // accountRepository
            mockk(relaxed = true), // tweetRepository
            mockk(relaxed = true), // rulesetEngine
            mockk(relaxed = true), // rateLimiter
            mockk(relaxed = true), // logDao
            mockk(relaxed = true), // configRepository
            mockk(relaxed = true), // feedbackController
            mockk(relaxed = true), // readAccess
            mockk(relaxed = true), // userActionTracker
            mockk(relaxed = true), // sessionManager
        )
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
    }
}
