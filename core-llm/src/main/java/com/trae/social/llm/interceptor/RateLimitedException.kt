package com.trae.social.llm.interceptor

import java.io.IOException

/**
 * LLM 提供商返回 429（限流）时抛出（IMPL-19）。
 *
 * 继承 [IOException] 而非 RuntimeException：OkHttp 的 AsyncCall 对非 IOException 的
 * Throwable 会在调用 onFailure 之后重新抛出原异常，导致 "OkHttp Dispatcher" 线程出现
 * 未捕获异常，进而触发 [com.trae.social.app.SocialApp] 注册的全局未捕获异常处理器
 * 终止进程（即用户反馈的"闪退"）。改为 IOException 后，429 会被 OkHttp 经 onFailure
 * 正常传递给调用方，各 Worker 仍可按类型精确捕获并跳过本次调度。
 *
 * 与普通 [IOException] 区分：调用方（Worker）捕获后应直接跳过本次调度，
 * 不重试、不消耗退避配额。
 *
 * @param retryAfterSeconds 服务端 Retry-After 头（秒），可能为 null。
 */
class RateLimitedException(
    message: String,
    val retryAfterSeconds: Long? = null,
    /**
     * 触发限流的原始异常（OpenAI / Anthropic SDK 的 RateLimitException 等）。
     *
     * 主 review 第 2 轮修复：保留原始 stacktrace 便于排查 429 来源。
     */
    cause: Throwable? = null,
) : IOException(message, cause)
