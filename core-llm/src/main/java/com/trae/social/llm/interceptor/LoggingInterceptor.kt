package com.trae.social.llm.interceptor

import com.trae.social.llm.maskApiKey
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

/**
 * 日志拦截器：仅在有 Timber 树时（debug 构建）记录请求方法、URL、状态码与耗时。
 *
 * 安全要求（RISK-11）：
 * - 不打印请求体（可能含敏感内容）；
 * - Authorization / x-api-key 头不输出（本拦截器不读取 header）；
 * - URL 中 query 参数 `key` 脱敏为 "前4...后4"。
 */
class LoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Timber.treeCount == 0 表示无树（release 不种植），跳过日志与 URL 脱敏计算
        if (Timber.treeCount == 0) {
            return chain.proceed(request)
        }

        val started = System.nanoTime()
        val safeUrl = sanitizeUrl(request.url.toString())
        val method = request.method

        val response: Response
        var tookMs: Long
        try {
            response = chain.proceed(request)
            tookMs = ((System.nanoTime() - started) / 1_000_000).toInt().toLong()
        } catch (t: Throwable) {
            tookMs = ((System.nanoTime() - started) / 1_000_000).toInt().toLong()
            Timber.d("--> %s %s FAIL (%dms): %s", method, safeUrl, tookMs, t.message)
            throw t
        }

        Timber.d("<-- %d %s %s (%dms)", response.code, method, safeUrl, tookMs)
        return response
    }

    private fun sanitizeUrl(url: String): String {
        if (url.isEmpty()) return url
        val parts = url.split("?")
        if (parts.size == 1) return url

        val base = parts[0]
        val query = parts.subList(1, parts.size).joinToString("?")
        val sanitizedParams = query.split("&").joinToString("&") { param ->
            val eq = param.indexOf('=')
            if (eq < 0) return@joinToString param
            val name = param.substring(0, eq)
            val value = param.substring(eq + 1)
            if (name.equals("key", ignoreCase = true)) {
                "$name=${maskApiKey(value)}"
            } else {
                param
            }
        }
        return "$base?$sanitizedParams"
    }
}
