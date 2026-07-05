package com.trae.social.core.data.config

/**
 * LLM 提供商标识。
 *
 * 持久化时以 [id] 字符串存入 DataStore，避免与 core-llm 模块的枚举耦合。
 */
enum class LlmProvider(val id: String, val displayName: String) {
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic"),
    GEMINI("gemini", "Google Gemini"),
    CUSTOM("custom", "自定义端点");

    companion object {
        fun fromId(id: String?): LlmProvider? = values().find { it.id == id }
    }
}

/**
 * AI 活跃度档位（RISK-1：控制整体调用频率）。
 *
 * - LOW：默认 10 RPM，每账号 2 条/日
 * - MEDIUM：默认 30 RPM，每账号 4 条/日
 * - HIGH：默认 60 RPM，每账号 8 条/日
 */
enum class AiActivityLevel(val id: String, val rpmLimit: Int, val dailyPostsPerAccount: Int) {
    LOW("low", rpmLimit = 10, dailyPostsPerAccount = 2),
    MEDIUM("medium", rpmLimit = 30, dailyPostsPerAccount = 4),
    HIGH("high", rpmLimit = 60, dailyPostsPerAccount = 8);

    companion object {
        fun fromId(id: String?): AiActivityLevel? = values().find { it.id == id }
    }
}
