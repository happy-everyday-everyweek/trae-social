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
 * 把 API Key 脱敏为 "前N...后N" 形式，长度不足时仅显示星号。
 *
 * 脱敏策略（避免短密钥近乎明文泄漏）：
 * - 空白返回 "<empty>"。
 * - 长度 <= 12 的短密钥只显示末 2 位，前缀星号，避免 9-10 位短 token 暴露 8 位。
 * - 较长密钥按长度 1/4（最多 4 位）前后各显示相同位数，标准 40+ 位密钥暴露 8 位，
 *   短密钥按比例减少暴露位数。
 */
fun maskApiKey(key: String?): String {
    if (key.isNullOrBlank()) return "<empty>"
    val trimmed = key.trim()
    if (trimmed.length <= 12) return "***..." + trimmed.takeLast(2)
    val showLen = (trimmed.length / 4).coerceAtMost(4)
    return trimmed.take(showLen) + "..." + trimmed.takeLast(showLen)
}
