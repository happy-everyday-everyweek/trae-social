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
 * - LOW：默认 10 RPM，每账号 2 条/日，人设更新 10 账号/14 天
 * - MEDIUM：默认 30 RPM，每账号 4 条/日，人设更新 20 账号/7 天
 * - HIGH：默认 60 RPM，每账号 8 条/日，人设更新 40 账号/3 天
 *
 * IMPL-47：[personaUpdateBatchSize] 与 [personaUpdatePeriodDays] 按档位缩放，
 * 避免 LOW/HIGH 档位下人设更新工作量相同。
 */
enum class AiActivityLevel(
    val id: String,
    val rpmLimit: Int,
    val dailyPostsPerAccount: Int,
    val personaUpdateBatchSize: Int,
    val personaUpdatePeriodDays: Int,
) {
    LOW("low", rpmLimit = 10, dailyPostsPerAccount = 2, personaUpdateBatchSize = 10, personaUpdatePeriodDays = 14),
    MEDIUM("medium", rpmLimit = 30, dailyPostsPerAccount = 4, personaUpdateBatchSize = 20, personaUpdatePeriodDays = 7),
    HIGH("high", rpmLimit = 60, dailyPostsPerAccount = 8, personaUpdateBatchSize = 40, personaUpdatePeriodDays = 3);

    companion object {
        fun fromId(id: String?): AiActivityLevel? = values().find { it.id == id }
    }
}
