package com.trae.social.llm.interceptor

import com.trae.social.llm.ratelimit.RateLimiter
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 限流拦截器：在请求发出前从全局令牌桶获取一个令牌，不足时挂起等待。
 *
 * 配合 [RetryInterceptor] 应对 RISK-1（配额超限）。
 */
class RateLimitInterceptor(
    private val rateLimiter: RateLimiter,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        runBlocking { rateLimiter.acquire() }
        return chain.proceed(chain.request())
    }
}
