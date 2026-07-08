package com.trae.social.llm.interceptor

import com.trae.social.llm.LlmHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/**
 * 重试拦截器：对 5xx（服务端错误）执行指数退避重试，
 * 最多 [LlmHttp.MAX_RETRY_ATTEMPTS] 次（含首次）。
 *
 * 429（限流）直接抛 [RateLimitedException]，不重试（IMPL-19）——
 * 重试只会浪费配额，应由调用方跳过本次调度。
 *
 * 退避公式：base * 2^(attempt-1) 毫秒。
 * 5xx 时优先读取 Retry-After 头（秒）。
 *
 * IMPL-7：不再返回已关闭的 Response——重试耗尽时抛 [IOException]，
 * 让调用方明确感知失败，而非读取已关闭 body 得到空字符串。
 *
 * 配合 [com.trae.social.llm.ratelimit.RateLimiter] 主动限流，应对 RISK-1。
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
                // 429：直接抛 RateLimitedException，不重试（IMPL-19）
                if (response.code == 429) {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    response.close()
                    throw RateLimitedException(
                        "LLM 提供商返回 429 限流",
                        retryAfterSeconds = retryAfter,
                    )
                }
                // 5xx：关闭当前响应后重试
                if (response.code in 500..599) {
                    val backoff = computeBackoff(response, attempt)
                    val errorCode = response.code
                    response.close()
                    if (attempt >= maxAttempts) {
                        Timber.w("5xx 重试耗尽 attempt=%d code=%d", attempt, errorCode)
                        throw IOException("server error $errorCode after $attempt attempts")
                    }
                    // IMPL-32：记录每次重试的状态码与退避时间，提升可观测性
                    Timber.d("5xx 重试 attempt=%d/%d code=%d backoff=%dms", attempt, maxAttempts, errorCode, backoff)
                    runBlocking { sleeper(backoff) }
                    continue
                }
                return response
            } catch (e: RateLimitedException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                if (attempt >= maxAttempts) {
                    Timber.w("IO 错误重试耗尽 attempt=%d err=%s", attempt, e.message)
                    break
                }
                val backoff = baseDelayMs * (1L shl (attempt - 1))
                Timber.d("IO 错误重试 attempt=%d/%d backoff=%dms err=%s", attempt, maxAttempts, backoff, e.message)
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
