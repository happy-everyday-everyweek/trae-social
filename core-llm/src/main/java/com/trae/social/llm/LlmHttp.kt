package com.trae.social.llm

/**
 * LLM 网络层全局常量。
 */
object LlmHttp {
    /** 连接超时（秒）。 */
    const val CONNECT_TIMEOUT_SECONDS = 15L
    /** 读取超时（秒），LLM 长响应需要更长时间。 */
    const val READ_TIMEOUT_SECONDS = 60L
    /** 写入超时（秒）。 */
    const val WRITE_TIMEOUT_SECONDS = 30L

    /** 默认调用速率（每分钟请求数）。 */
    const val DEFAULT_RPM = 30

    /** 重试最大次数（含首次请求）。 */
    const val MAX_RETRY_ATTEMPTS = 3
    /** 退避基准时间（毫秒）。 */
    const val RETRY_BASE_DELAY_MS = 500L

    /** 各提供商默认 Base URL。 */
    const val OPENAI_BASE_URL = "https://api.openai.com/"
    const val ANTHROPIC_BASE_URL = "https://api.anthropic.com/"
    const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/"

    /** Anthropic API 版本头。 */
    const val ANTHROPIC_VERSION = "2023-06-01"

    /** 用于标记请求所属提供商的内部头（不发送到网络，仅拦截器读取后移除）。 */
    const val PROVIDER_HEADER = "X-Llm-Provider"
}

/**
 * 把 API Key 脱敏为 "前4...后4" 形式，长度不足时仅显示星号。
 */
fun maskApiKey(key: String?): String {
    if (key.isNullOrBlank()) return "<empty>"
    val trimmed = key.trim()
    if (trimmed.length <= 8) return "***"
    return trimmed.take(4) + "..." + trimmed.takeLast(4)
}
