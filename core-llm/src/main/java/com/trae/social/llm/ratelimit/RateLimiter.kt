package com.trae.social.llm.ratelimit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 令牌桶限流器，控制全局 LLM 调用频率（默认 30 RPM）。
 *
 * 实现：固定容量桶，按 [refillIntervalMillis] 周期性补充令牌至 [maxTokens]。
 * [tryAcquire] 非阻塞立即返回；[acquire] 阻塞挂起至有可用令牌。
 *
 * 用于应对 RISK-1（配额超限）。
 */
class RateLimiter(
    private val maxTokens: Int = 30,
    private val refillIntervalMillis: Long = 60_000L,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxTokens > 0) { "maxTokens must be > 0" }
        require(refillIntervalMillis > 0) { "refillIntervalMillis must be > 0" }
    }

    private val mutex = Mutex()
    private var availableTokens: Int = maxTokens
    private var lastRefillTimestamp: Long = nowProvider()

    /**
     * 阻塞获取一个令牌，挂起至令牌可用。
     */
    suspend fun acquire() {
        while (!tryAcquire()) {
            // 令牌不足时短暂让出执行权再重试
            kotlinx.coroutines.delay(CHECK_INTERVAL_MS)
        }
    }

    /**
     * 非阻塞尝试获取一个令牌，成功返回 true。
     */
    suspend fun tryAcquire(): Boolean = mutex.withLock {
        refillLocked()
        if (availableTokens > 0) {
            availableTokens -= 1
            true
        } else {
            false
        }
    }

    /** 当前可用令牌数（主要用于测试与监控）。 */
    suspend fun availableTokens(): Int = mutex.withLock {
        refillLocked()
        availableTokens
    }

    private fun refillLocked() {
        val now = nowProvider()
        val elapsed = now - lastRefillTimestamp
        if (elapsed >= refillIntervalMillis) {
            // 按经过的完整周期补充
            val cycles = (elapsed / refillIntervalMillis).toInt()
            if (cycles > 0) {
                availableTokens = (availableTokens + cycles).coerceAtMost(maxTokens)
                lastRefillTimestamp += cycles * refillIntervalMillis
            }
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 50L
    }
}
