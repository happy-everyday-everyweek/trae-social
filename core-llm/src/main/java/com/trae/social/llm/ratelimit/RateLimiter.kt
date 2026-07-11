package com.trae.social.llm.ratelimit

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 令牌桶限流器，控制全局 LLM 调用频率（默认 30 RPM）。
 *
 * 实现：固定容量桶，按 [refillIntervalMillis] 周期性补充令牌至 [maxTokens]。
 * [tryAcquire] 非阻塞立即返回；[acquire] 阻塞挂起至有可用令牌。
 *
 * IMPL-31：[acquire] 计算到下次补充的精确等待时间，避免 50ms 轮询忙等；
 * [refillLocked] 处理时钟回拨（elapsed 为负时重置基准时间）。
 *
 * IMPL-26：[reconfigure] 支持运行时调整容量，按比例折算保留令牌。
 *
 * 用于应对 RISK-1（配额超限）。
 */
class RateLimiter(
    private var maxTokens: Int = 30,
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
     *
     * IMPL-31：计算到下次令牌补充的精确等待时间，避免 50ms 轮询忙等。
     */
    suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                refillLocked()
                if (availableTokens > 0) {
                    availableTokens -= 1
                    return
                }
                // 计算到下次补充的精确等待时间
                val elapsed = nowProvider() - lastRefillTimestamp
                refillIntervalMillis - (elapsed % refillIntervalMillis)
            }
            delay(waitMs.coerceAtLeast(1))
        }
    }

    /**
     * #93：带超时的阻塞获取令牌，超时未获取到则返回 false。
     *
     * Worker 可在调用前用此方法避免因限流阻塞超过 10 分钟 Worker 超时上限。
     * 返回 false 时调用方应主动放弃本次执行（Result.retry()）。
     */
    suspend fun acquireWithTimeout(timeoutMillis: Long): Boolean {
        val deadline = nowProvider() + timeoutMillis
        while (true) {
            val waitMs = mutex.withLock {
                refillLocked()
                if (availableTokens > 0) {
                    availableTokens -= 1
                    return true
                }
                if (nowProvider() >= deadline) return false
                val elapsed = nowProvider() - lastRefillTimestamp
                (refillIntervalMillis - (elapsed % refillIntervalMillis)).coerceAtMost(deadline - nowProvider())
            }
            delay(waitMs.coerceAtLeast(1))
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

    /**
     * 重新配置令牌桶容量，按比例折算保留当前可用令牌。
     *
     * IMPL-26：支持运行时按 [com.trae.social.core.data.config.AiActivityLevel] 调整 RPM。
     * IMPL-30：不替换实例，避免并发场景下令牌丢失。
     */
    suspend fun reconfigure(newMaxTokens: Int) {
        require(newMaxTokens > 0) { "newMaxTokens must be > 0" }
        mutex.withLock {
            if (newMaxTokens == maxTokens) return@withLock
            // 按比例折算保留令牌，避免切换档位时令牌暴增或丢失
            val scaled = (availableTokens.toLong() * newMaxTokens / maxTokens).toInt()
            maxTokens = newMaxTokens
            availableTokens = scaled.coerceAtMost(newMaxTokens)
        }
    }

    private fun refillLocked() {
        val now = nowProvider()
        val elapsed = now - lastRefillTimestamp
        // IMPL-31：处理时钟回拨——elapsed 为负时重置基准时间
        if (elapsed < 0) {
            lastRefillTimestamp = now
            return
        }
        if (elapsed >= refillIntervalMillis) {
            // #119：每个补充周期补满到 maxTokens（与 RPM 语义一致），而非每周期只补 1 个
            // M1 修复：cycles * maxTokens 为 Int×Int，长期不活跃（~25天）会溢出为负值，
            // 导致 availableTokens 变为大负数后限流器永久失效。
            // cycles>=1 时 cycles*maxTokens >= maxTokens 必然被 coerceAtMost 截到 maxTokens，
            // 故直接赋值 maxTokens 即可，无需乘法。
            val cycles = (elapsed / refillIntervalMillis).toInt()
            if (cycles > 0) {
                availableTokens = maxTokens
                lastRefillTimestamp += cycles * refillIntervalMillis
            }
        }
    }
}
