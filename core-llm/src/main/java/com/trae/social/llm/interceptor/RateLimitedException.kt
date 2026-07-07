package com.trae.social.llm.interceptor

/**
 * LLM 提供商返回 429（限流）时抛出（IMPL-19）。
 *
 * 与普通 [java.io.IOException] 区分：调用方（Worker）捕获后应直接跳过本次调度，
 * 不重试、不消耗退避配额。
 *
 * @param retryAfterSeconds 服务端 Retry-After 头（秒），可能为 null。
 */
class RateLimitedException(
    message: String,
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message)
