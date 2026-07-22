package com.trae.social.llm

import com.trae.social.llm.interceptor.RateLimitedException
import java.io.IOException

/**
 * SDK 异常分类工具（#308：消除 [DefaultRulesetEngine] / [openai.OpenAiCompatibleClient] /
 * [anthropic.AnthropicCompatibleClient] / `OnboardingViewModel.classifyError` 间
 * `extractSdkStatusCode` / `isPersistentError` / `isRateLimited` 的四处重复定义）。
 *
 * OpenAI Java SDK 的 `com.openai.errors.OpenAIServiceException` 与 Anthropic Java SDK 的
 * `com.anthropic.errors.AnthropicServiceException` 均暴露 `int statusCode()` 方法（已通过
 * javap 验证），子类（`BadRequestException` / `UnauthorizedException` / `RateLimitException`
 * 等）继承并 override 该方法。本工具通过反射读取该方法，避免本模块直接依赖 SDK errors 子包，
 * 也避免旧实现用 `className.contains("OpenAI"/"Anthropic")` 匹配（SDK 异常 simpleName 不含
 * 厂商前缀，匹配结果依赖 simpleName vs qualifiedName 与大小写敏感性，不够稳健）。
 *
 * **设计为 `object`（无状态单例）**：所有方法纯函数，无 mutable 状态，线程安全。
 */
object SdkExceptionClassifier {

    /**
     * HTTP 429 Too Many Requests 状态码常量（避免魔数散落各处，#285）。
     */
    const val HTTP_TOO_MANY_REQUESTS = 429

    /**
     * 通过反射读取 SDK 异常的 `statusCode()` 方法。
     *
     * - OpenAI / Anthropic SDK 的 `OpenAIServiceException` / `AnthropicServiceException`
     *   及其子类均暴露 `int statusCode()`，反射命中即返回。
     * - 非 SDK 异常（如 `IOException`）无此方法，返回 null 由调用方走可恢复错误降级路径。
     * - `runCatching` 仅吞 `NoSuchMethodException` / `SecurityException` / `IllegalAccessException`
     *   等反射异常；`method.invoke(e)` 抛出的业务异常（理论上 `statusCode()` 不应抛业务异常，
     *   但稳健起见）也被吞为 null。
     */
    fun extractStatusCode(e: Throwable): Int? = runCatching {
        val method = e::class.java.getMethod("statusCode")
        // SDK 的 statusCode() 返回 int（autobox 为 Integer），统一按 Number 取值，
        // 避免对 method.invoke(e) 二次调用产生的开销与潜在副作用。
        (method.invoke(e) as? Number)?.toInt()
    }.getOrNull()

    /**
     * 判断异常是否为持久性 HTTP 错误（4xx 非 429）。
     *
     * 持久性错误不应触发降级重试——重试只会再得到同样的 4xx，浪费配额。
     *
     * - `IOException`（UnknownHost / SocketTimeout 等）一律不视为持久性错误：旧实现会从
     *   IOException message 里抠 3 位数字（如 "Failed to connect to /10.0.0.401:443" 命中 401）
     *   误判为持久性 HTTP 错误直接抛出不降级，与 `OnboardingViewModel.classifyError` 的分层
     *   逻辑对齐——网络错误应允许降级到下一端点重试。
     * - [RateLimitedException] 是 `IOException` 子类，应在 [isRateLimited] 中优先判定，
     *   不会走到这里。
     * - 非 SDK 异常（反射未命中）一律返回 false（按可恢复错误处理，允许降级重试）。
     * - 不再使用 message 正则兜底（旧实现的 `extractHttpCode`）：非 SDK 异常的 message 含 3 位
     *   数字（如 "Port 443 in use" / "error at line 503"）会被误判为 HTTP 4xx/5xx。
     */
    fun isPersistentError(e: Throwable): Boolean {
        if (e is IOException) return false
        val code = extractStatusCode(e) ?: return false
        return code in 400..499 && code != HTTP_TOO_MANY_REQUESTS
    }

    /**
     * 判断异常是否为限流（429）。
     *
     * - [RateLimitedException]（由 [DefaultRulesetEngine.toRateLimited] 包装）直接返回 true。
     * - SDK 抛出的原始 429 异常（`OpenAIServiceException.statusCode() == 429` 等）通过反射判定。
     * - `IOException`（UnknownHost / SocketTimeout 等）的 message 偶然含 "429" 不应被误判为限流。
     */
    fun isRateLimited(e: Throwable): Boolean {
        if (e is RateLimitedException) return true
        if (e is IOException) return false
        val code = extractStatusCode(e) ?: return false
        return code == HTTP_TOO_MANY_REQUESTS
    }
}
