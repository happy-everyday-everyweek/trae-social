package com.trae.social.core.scheduler.ratelimit

import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.repository.TweetRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [DailyQuotaChecker] 单元测试。
 *
 * 覆盖：配额未达上限、配额已达上限、不同活跃度档位的配额边界。
 */
class DailyQuotaCheckerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun now(): Instant =
        ZonedDateTime.of(2026, 7, 5, 10, 0, 0, 0, zone).toInstant()

    @Test
    fun `当日推文数低于档位上限时未耗尽配额`() = runTest {
        val tweetRepo = mockk<TweetRepository>()
        coEvery { tweetRepo.countByAuthorSince(any(), any()) } returns 1
        val checker = DailyQuotaChecker(tweetRepo, zone)

        val exhausted = checker.isQuotaExhausted(
            accountId = "acc1",
            level = AiActivityLevel.LOW,
            now = now(),
        )
        assertFalse("1 < 2 应未耗尽", exhausted)
    }

    @Test
    fun `当日推文数等于档位上限时已耗尽配额`() = runTest {
        val tweetRepo = mockk<TweetRepository>()
        coEvery { tweetRepo.countByAuthorSince(any(), any()) } returns 2
        val checker = DailyQuotaChecker(tweetRepo, zone)

        val exhausted = checker.isQuotaExhausted(
            accountId = "acc1",
            level = AiActivityLevel.LOW,
            now = now(),
        )
        assertTrue("2 >= 2 应已耗尽", exhausted)
    }

    @Test
    fun `当日推文数超过档位上限时已耗尽配额`() = runTest {
        val tweetRepo = mockk<TweetRepository>()
        coEvery { tweetRepo.countByAuthorSince(any(), any()) } returns 5
        val checker = DailyQuotaChecker(tweetRepo, zone)

        val exhausted = checker.isQuotaExhausted(
            accountId = "acc1",
            level = AiActivityLevel.MEDIUM,
            now = now(),
        )
        assertTrue("5 >= 4 应已耗尽", exhausted)
    }

    @Test
    fun `HIGH 档位 8 条上限边界`() = runTest {
        val tweetRepo = mockk<TweetRepository>()
        coEvery { tweetRepo.countByAuthorSince(any(), any()) } returns 8
        val checker = DailyQuotaChecker(tweetRepo, zone)

        assertTrue(
            "8 >= 8 应已耗尽",
            checker.isQuotaExhausted("acc1", AiActivityLevel.HIGH, now())
        )

        coEvery { tweetRepo.countByAuthorSince(any(), any()) } returns 7
        assertFalse(
            "7 < 8 应未耗尽",
            checker.isQuotaExhausted("acc1", AiActivityLevel.HIGH, now())
        )
    }

    @Test
    fun `usedToday 返回当日已发布推文数`() = runTest {
        val tweetRepo = mockk<TweetRepository>()
        coEvery { tweetRepo.countByAuthorSince(any(), any()) } returns 3
        val checker = DailyQuotaChecker(tweetRepo, zone)

        val used = checker.usedToday("acc1", now())
        assertEquals(3, used)
    }

    @Test
    fun `不同档位配额上限正确`() {
        assertEquals(2, AiActivityLevel.LOW.dailyPostsPerAccount)
        assertEquals(4, AiActivityLevel.MEDIUM.dailyPostsPerAccount)
        assertEquals(8, AiActivityLevel.HIGH.dailyPostsPerAccount)
        assertEquals(10, AiActivityLevel.LOW.rpmLimit)
        assertEquals(30, AiActivityLevel.MEDIUM.rpmLimit)
        assertEquals(60, AiActivityLevel.HIGH.rpmLimit)
    }
}
