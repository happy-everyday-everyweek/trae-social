package com.trae.social.llm

import com.trae.social.llm.interceptor.RateLimitedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [SdkExceptionClassifier] 单元测试（#308：验证抽到共享工具后的反射分类逻辑，
 * 同时作为 DefaultRulesetEngine / OpenAi / Anthropic client / OnboardingViewModel
 * 四处共用契约的回归保护）。
 */
class SdkExceptionClassifierTest {

    @Test
    fun `extractStatusCode SDK 异常返回 statusCode`() {
        val ex = FakeSdkException(401)
        assertEquals(401, SdkExceptionClassifier.extractStatusCode(ex))
    }

    @Test
    fun `extractStatusCode 非 SDK 异常返回 null`() {
        val ex = IOException("network error")
        assertNull(SdkExceptionClassifier.extractStatusCode(ex))
        assertNull(SdkExceptionClassifier.extractStatusCode(RuntimeException("oops")))
    }

    @Test
    fun `isPersistentError 4xx 非 429 返回 true`() {
        assertTrue(SdkExceptionClassifier.isPersistentError(FakeSdkException(400)))
        assertTrue(SdkExceptionClassifier.isPersistentError(FakeSdkException(401)))
        assertTrue(SdkExceptionClassifier.isPersistentError(FakeSdkException(403)))
        assertTrue(SdkExceptionClassifier.isPersistentError(FakeSdkException(404)))
        assertTrue(SdkExceptionClassifier.isPersistentError(FakeSdkException(422)))
        assertTrue(SdkExceptionClassifier.isPersistentError(FakeSdkException(499)))
    }

    @Test
    fun `isPersistentError 429 返回 false（429 走 isRateLimited）`() {
        assertFalse(SdkExceptionClassifier.isPersistentError(FakeSdkException(429)))
    }

    @Test
    fun `isPersistentError 5xx 返回 false（按可恢复错误处理）`() {
        assertFalse(SdkExceptionClassifier.isPersistentError(FakeSdkException(500)))
        assertFalse(SdkExceptionClassifier.isPersistentError(FakeSdkException(502)))
        assertFalse(SdkExceptionClassifier.isPersistentError(FakeSdkException(503)))
    }

    @Test
    fun `isPersistentError IOException 一律返回 false（即使 message 含 3 位数字）`() {
        // 旧实现从 IOException message 抠 3 位数字误判为持久性 HTTP 错误，导致降级链失效。
        // 真实场景如 "Failed to connect to /10.0.0.401:443" 含 401 不应被当成 HTTP 401。
        assertFalse(SdkExceptionClassifier.isPersistentError(IOException("Failed to connect to /10.0.0.401:443")))
        assertFalse(SdkExceptionClassifier.isPersistentError(IOException("Port 443 in use")))
        assertFalse(SdkExceptionClassifier.isPersistentError(IOException("plain network error")))
    }

    @Test
    fun `isPersistentError 非 SDK 异常返回 false（不信任 message 正则）`() {
        // 旧实现用 message 正则兜底会把 "Port 443 in use" / "error at line 503" 误判为 HTTP 4xx/5xx
        assertFalse(SdkExceptionClassifier.isPersistentError(RuntimeException("error at line 503")))
        assertFalse(SdkExceptionClassifier.isPersistentError(IllegalStateException("Port 443 in use")))
    }

    @Test
    fun `isRateLimited RateLimitedException 直接返回 true`() {
        assertTrue(SdkExceptionClassifier.isRateLimited(RateLimitedException("limited")))
    }

    @Test
    fun `isRateLimited SDK 429 返回 true`() {
        assertTrue(SdkExceptionClassifier.isRateLimited(FakeSdkException(429)))
    }

    @Test
    fun `isRateLimited SDK 非 429 返回 false`() {
        assertFalse(SdkExceptionClassifier.isRateLimited(FakeSdkException(401)))
        assertFalse(SdkExceptionClassifier.isRateLimited(FakeSdkException(500)))
    }

    @Test
    fun `isRateLimited IOException 即使 message 含 429 也返回 false`() {
        // 防止 IOException message 偶然含 "429" 被误判为限流（实际是网络错误）
        assertFalse(SdkExceptionClassifier.isRateLimited(IOException("error 429 retry")))
        assertFalse(SdkExceptionClassifier.isRateLimited(IOException("timeout")))
    }

    @Test
    fun `isRateLimited 非 SDK 异常返回 false`() {
        assertFalse(SdkExceptionClassifier.isRateLimited(RuntimeException("oops")))
    }

    /**
     * 模拟 OpenAI / Anthropic SDK 的 Service 异常基类（带 statusCode() 方法）。
     */
    private class FakeSdkException(private val code: Int) : Exception("HTTP $code") {
        @Suppress("unused") // 由 SdkExceptionClassifier 通过反射调用
        fun statusCode(): Int = code
    }
}
