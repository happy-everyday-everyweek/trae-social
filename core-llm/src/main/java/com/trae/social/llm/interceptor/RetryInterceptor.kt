package com.trae.social.llm.interceptor

import com.trae.social.llm.LlmHttp
import com.trae.social.llm.ratelimit.RateLimiter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 重试拦截器：对 429（限流）与 5xx（服务端错误）执行指数退避重试，
 * 最多 [LlmHttp.MAX_RETRY_ATTEMPTS] 次（含首次）。
 *
 * 退避公式：base * 2^(attempt-1) 毫秒。
 * 429 时优先读取 Retry-After 头（秒）。
 *
 * 配合 [RateLimiter] 主动限流，应对 RISK-1。
 */
class RetryInterceptor(
    private val maxAttempts: Int = LlmHttp.MAX_RETRY_ATTEMPTS,
    private val baseDelayMs: Long = LlmHttp.RETRY_BASE_DELAY_MS,
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var lastError: IOException? = null
        while (attempt < maxAttempts) {
            attempt++
            try {
                val response = chain.proceed(chain.request())
                if (response.code == 429 || response.code in 500..599) {
                    response.close()
                    if (attempt >= maxAttempts) {
                        return response
                    }
                    val backoff = computeBackoff(response, attempt)
                    runBlocking { sleeper(backoff) }
                    continue
                }
                return response
            } catch (e: IOException) {
                lastError = e
                if (attempt >= maxAttempts) break
                val backoff = baseDelayMs * (1L shl (attempt - 1))
                runBlocking { sleeper(backoff) }
            }
        }
        throw lastError ?: IOException("retry exhausted")
    }

    private fun computeBackoff(response: Response, attempt: Int): Long {
        val retryAfter = response.header("Retry-After")?.toLongOrNull()
        if (retryAfter != null) {
            return retryAfter.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000L
        }
        return baseDelayMs * (1L shl (attempt - 1))
    }

    private companion object {
        const val MAX_RETRY_AFTER_SECONDS = 60L
    }
}
