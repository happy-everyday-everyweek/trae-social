package com.trae.social.core.scheduler.ratelimit

import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.llm.ratelimit.RateLimiter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 调度层限流器：包装 core-llm 的 [RateLimiter]，按 [AiActivityLevel] 配置容量。
 *
 * IMPL-30：切换 AI 活跃度档位时调用 [reconfigure] 原地调整底层令牌桶容量
 * （不替换实例），按比例折算保留令牌，避免并发场景下互相覆盖丢令牌。
 * 单例由 [com.trae.social.core.scheduler.di.SchedulerModule] 提供。
 *
 * 注意：HTTP 层 RateLimitInterceptor 已移除（IMPL-26 统一为一层限流），
 * 本类是唯一的限流闸门，在调度入口处 acquire，避免无效请求消耗网络栈资源。
 */
class SchedulerRateLimiter(
    initialLevel: AiActivityLevel = AiActivityLevel.MEDIUM,
) {

    private val rateLimiter: RateLimiter = RateLimiter(
        maxTokens = initialLevel.rpmLimit,
        refillIntervalMillis = REFILL_INTERVAL_MILLIS,
    )

    @Volatile
    private var level: AiActivityLevel = initialLevel

    private val mutex = Mutex()

    /**
     * 按新档位原地调整令牌桶容量，按比例折算保留令牌。
     * IMPL-30：使用 Mutex 保证并发安全，不替换底层实例。
     */
    suspend fun reconfigure(newLevel: AiActivityLevel) {
        if (newLevel == level) return
        mutex.withLock {
            if (newLevel == level) return@withLock
            level = newLevel
            rateLimiter.reconfigure(newLevel.rpmLimit)
        }
    }

    /**
     * 阻塞获取一个令牌，挂起至令牌可用。
     */
    suspend fun acquire() = rateLimiter.acquire()

    /**
     * 非阻塞尝试获取一个令牌，成功返回 true。
     */
    suspend fun tryAcquire(): Boolean = rateLimiter.tryAcquire()

    /**
     * 当前生效的活跃度档位。
     */
    fun currentLevel(): AiActivityLevel = level

    /**
     * 当前可用令牌数（主要用于测试与监控）。
     */
    suspend fun availableTokens(): Int = rateLimiter.availableTokens()

    private companion object {
        // 1 分钟补充一次令牌至容量上限（与 RPM 语义一致）
        const val REFILL_INTERVAL_MILLIS: Long = 60_000L
    }
}
