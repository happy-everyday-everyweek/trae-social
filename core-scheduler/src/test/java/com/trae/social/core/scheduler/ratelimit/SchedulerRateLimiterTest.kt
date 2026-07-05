package com.trae.social.core.scheduler.ratelimit

import com.trae.social.core.data.config.AiActivityLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SchedulerRateLimiter] 单元测试。
 *
 * 覆盖：默认档位、reconfigure 切换档位、tryAcquire 令牌消耗。
 */
class SchedulerRateLimiterTest {

    @Test
    fun `默认档位为 MEDIUM`() {
        val limiter = SchedulerRateLimiter()
        assertEquals(AiActivityLevel.MEDIUM, limiter.currentLevel())
    }

    @Test
    fun `reconfigure 切换档位`() = runTest {
        val limiter = SchedulerRateLimiter(initialLevel = AiActivityLevel.LOW)
        assertEquals(AiActivityLevel.LOW, limiter.currentLevel())
        // 初始容量 10
        assertEquals(10, limiter.availableTokens())

        limiter.reconfigure(AiActivityLevel.HIGH)
        assertEquals(AiActivityLevel.HIGH, limiter.currentLevel())
        // 切换后容量重建为 60
        assertEquals(60, limiter.availableTokens())
    }

    @Test
    fun `重复 reconfigure 同一档位不重建`() = runTest {
        val limiter = SchedulerRateLimiter(initialLevel = AiActivityLevel.MEDIUM)
        // 消耗一个令牌
        limiter.tryAcquire()
        val tokensAfterAcquire = limiter.availableTokens()
        limiter.reconfigure(AiActivityLevel.MEDIUM)
        // 同档位不应重建，令牌数不变
        assertEquals(tokensAfterAcquire, limiter.availableTokens())
    }

    @Test
    fun `tryAcquire 消耗令牌直至耗尽`() = runTest {
        val limiter = SchedulerRateLimiter(initialLevel = AiActivityLevel.LOW) // 10 RPM
        var acquired = 0
        while (limiter.tryAcquire()) {
            acquired++
        }
        assertEquals(10, acquired)
        // 再 tryAcquire 应失败
        assertTrue(!limiter.tryAcquire())
    }

    @Test
    fun `不同档位初始容量不同`() = runTest {
        val low = SchedulerRateLimiter(initialLevel = AiActivityLevel.LOW)
        assertEquals(10, low.availableTokens())

        val medium = SchedulerRateLimiter(initialLevel = AiActivityLevel.MEDIUM)
        assertEquals(30, medium.availableTokens())

        val high = SchedulerRateLimiter(initialLevel = AiActivityLevel.HIGH)
        assertEquals(60, high.availableTokens())
    }
}
