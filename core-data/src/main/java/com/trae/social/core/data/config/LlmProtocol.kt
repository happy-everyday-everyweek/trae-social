package com.trae.social.core.data.config

/**
 * LLM 协议格式枚举（#151 重构核心）。
 *
 * 把「提供商」与「API 协议格式」解耦：用户配置的端点只需声明走哪种协议格式，
 * 不必绑定到特定提供商。OpenAI 兼容格式可由 OpenAI / Deepseek / Moonshot /
 * 智谱 / SiliconFlow / 本地 Ollama / Google Gemini（官方 OpenAI 兼容端点）等提供；
 * Anthropic 兼容格式也可由其他提供商提供。
 *
 * 不再保留 GEMINI_COMPATIBLE：Gemini 走 OPENAI_COMPATIBLE 的官方兼容端点
 * `https://generativelanguage.googleapis.com/v1beta/openai/`，由 OpenAI SDK 统一承载。
 *
 * @param id 持久化标识（存入 Room / DataStore）。
 * @param displayName UI 展示名。
 * @param defaultBaseUrl 该协议的官方默认 Base URL，未配置时使用。
 * @param requiresApiKey 该协议是否要求 API Key 才能创建 client。
 *   - `true`：API Key 缺失时 [com.trae.social.llm.EndpointRegistry] 直接跳过 client 创建
 *     （避免发起注定 401 的请求浪费配额）。所有 Anthropic 兼容端点均需鉴权。
 *   - `false`：API Key 缺失时仍尝试创建 client，让 SDK 自行处理空 Key
 *     （OpenAI SDK builder 内部 `apiKey?.takeIf { it.isNotBlank() }?.let { apiKey(it) }`
 *     会跳过 Authorization 头）。这覆盖了本地 Ollama 等无需鉴权的 OpenAI 兼容端点场景。
 *     OpenAI / Deepseek / Moonshot 等需鉴权的端点会自然返回 401，由调用方分类提示。
 */
enum class LlmProtocol(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val requiresApiKey: Boolean,
) {
    OPENAI_COMPATIBLE("openai_compat", "OpenAI 兼容", "https://api.openai.com/v1/", requiresApiKey = false),
    ANTHROPIC_COMPATIBLE("anthropic_compat", "Anthropic 兼容", "https://api.anthropic.com/", requiresApiKey = true);

    companion object {
        fun fromId(id: String?): LlmProtocol? = values().find { it.id == id }
    }
}
