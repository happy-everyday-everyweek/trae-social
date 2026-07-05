package com.trae.social.core.scheduler.ratelimit

import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.llm.ratelimit.RateLimiter

/**
 * 调度层限流器：包装 core-llm 的 [RateLimiter]，按 [AiActivityLevel] 配置容量。
 *
 * 切换 AI 活跃度档位时调用 [reconfigure] 重建底层令牌桶，保证全局 RPM 限制生效。
 * 单例由 [com.trae.social.core.scheduler.di.SchedulerModule] 提供。
 *
 * 注意：底层 [RateLimiter] 已嵌入 OkHttp 拦截器链（core-llm 模块），
 * 本类作为应用层的二级限流闸门，用于在调度入口处先 tryAcquire，
 * 避免无效请求消耗网络栈资源。
 */
class SchedulerRateLimiter(
    initialLevel: AiActivityLevel = AiActivityLevel.MEDIUM,
) {

    @Volatile
    private var current: RateLimiter = RateLimiter(
        maxTokens = initialLevel.rpmLimit,
        refillIntervalMillis = REFINED_INTERVAL_MILLIS,
    )

    @Volatile
    private var level: AiActivityLevel = initialLevel

    /**
     * 按新档位重建令牌桶。已缓存的令牌会被丢弃。
     */
    @Synchronized
    fun reconfigure(newLevel: AiActivityLevel) {
        if (newLevel == level) return
        level = newLevel
        current = RateLimiter(
            maxTokens = newLevel.rpmLimit,
            refillIntervalMillis = REFINED_INTERVAL_MILLIS,
        )
    }

    /**
     * 阻塞获取一个令牌，挂起至令牌可用。
     */
    suspend fun acquire() = current.acquire()

    /**
     * 非阻塞尝试获取一个令牌，成功返回 true。
     */
    suspend fun tryAcquire(): Boolean = current.tryAcquire()

    /**
     * 当前生效的活跃度档位。
     */
    fun currentLevel(): AiActivityLevel = level

    /**
     * 当前可用令牌数（主要用于测试与监控）。
     */
    suspend fun availableTokens(): Int = current.availableTokens()

    private companion object {
        // 1 分钟补充一次令牌至容量上限（与 RPM 语义一致）
        const val REFINED_INTERVAL_MILLIS: Long = 60_000L
    }
}
