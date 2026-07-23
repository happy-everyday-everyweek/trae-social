package com.trae.social.core.data.config

/**
 * LLM 提供商标识。
 *
 * 持久化时以 [id] 字符串存入 DataStore，避免与 core-llm 模块的枚举耦合。
 *
 * #287：此前各提供商的元数据（默认 Base URL / 默认模型 / 推荐模型列表 / 协议格式 /
 * 能力集合 / 获取 Key 链接 / UI 描述）散落在 OnboardingViewModel、KeyInputScreen、
 * ConfigRepository、ProviderSelectScreen 四个文件的独立 `when` 与 `Map` 中，新增一个
 * 提供商需同步修改多处（Shotgun Surgery）。现把全部元数据收敛到枚举自身，新增提供商
 * 只需添加一个枚举值。
 *
 * @param id 持久化标识。
 * @param displayName UI 展示名。
 * @param description 提供商一句话描述（供 ProviderSelectScreen 卡片展示）。
 * @param protocol 该提供商默认使用的 API 协议格式。
 * @param defaultBaseUrl 默认 Base URL（用户可在引导流程修改）。
 * @param defaultModel 默认推荐模型名（用户可修改）。
 * @param recommendedModels 推荐模型列表，供模型名输入下拉选择。
 * @param keyAcquireUrl 官方获取 API Key 的链接；[CUSTOM] 无官方链接返回 null。
 * @param apiKeyPrefix API Key 前缀特征（用于 [detectFromApiKey]）；[CUSTOM] 无特征返回 null。
 * @param capabilities 该提供商默认能力集合（迁移旧配置时使用）。
 */
enum class LlmProvider(
    val id: String,
    val displayName: String,
    val description: String,
    val protocol: LlmProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val recommendedModels: List<String>,
    val keyAcquireUrl: String?,
    val apiKeyPrefix: String?,
    val capabilities: Set<ModelCapability>,
) {
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        description = "GPT 系列模型，通用能力强，生态成熟",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.openai.com",
        defaultModel = "gpt-4o-mini",
        recommendedModels = listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo"),
        keyAcquireUrl = "https://platform.openai.com/api-keys",
        apiKeyPrefix = "sk-",
        capabilities = setOf(
            ModelCapability.TEXT,
            ModelCapability.JSON_MODE_NATIVE,
            ModelCapability.VISION_INPUT,
            ModelCapability.STREAMING,
        ),
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic",
        description = "Claude 系列模型，长文本与一致性表现出色",
        protocol = LlmProtocol.ANTHROPIC_COMPATIBLE,
        defaultBaseUrl = "https://api.anthropic.com",
        defaultModel = "claude-3-5-sonnet-20240620",
        recommendedModels = listOf(
            "claude-3-5-sonnet-20240620",
            "claude-3-5-haiku-20241022",
            "claude-3-opus-20240229",
        ),
        keyAcquireUrl = "https://console.anthropic.com/settings/keys",
        apiKeyPrefix = "sk-ant-",
        capabilities = setOf(
            ModelCapability.TEXT,
            ModelCapability.STREAMING,
        ),
    ),
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        description = "Gemini 系列模型，多模态能力突出",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        defaultModel = "gemini-1.5-flash",
        recommendedModels = listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash"),
        keyAcquireUrl = "https://aistudio.google.com/app/apikey",
        apiKeyPrefix = "AIza",
        capabilities = setOf(
            ModelCapability.TEXT,
            ModelCapability.JSON_MODE_NATIVE,
            ModelCapability.VISION_INPUT,
        ),
    ),
    CUSTOM(
        id = "custom",
        displayName = "自定义（OpenAI 兼容）",
        description = "兼容 OpenAI 协议的第三方端点，需填写 Base URL",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "",
        defaultModel = "gpt-4o-mini",
        recommendedModels = emptyList(),
        keyAcquireUrl = null,
        apiKeyPrefix = null,
        capabilities = setOf(
            ModelCapability.TEXT,
            ModelCapability.JSON_MODE_NATIVE,
            ModelCapability.VISION_INPUT,
            ModelCapability.STREAMING,
        ),
    );

    companion object {
        fun fromId(id: String?): LlmProvider? = values().find { it.id == id }

        /**
         * #34：根据 API Key 前缀识别所属提供商。
         *
         * - `sk-ant-` 开头（大小写不敏感）：[ANTHROPIC]
         * - `sk-` 开头（大小写不敏感，非 ant）：[OPENAI]
         * - `AIza` 开头（大小写敏感，避免 aiza/AIZA 误判）：[GEMINI]
         *
         * @return 识别到的提供商，未匹配返回 null。
         */
        fun detectFromApiKey(key: String): LlmProvider? {
            if (key.length < 4) return null
            // 注意顺序：ANTHROPIC(sk-ant-) 必须先于 OPENAI(sk-) 判断，否则会被 sk- 截获。
            // GEMINI(AIza) 大小写敏感，单独判断。
            return when {
                key.startsWith("sk-ant-", ignoreCase = true) -> ANTHROPIC
                key.startsWith("sk-", ignoreCase = true) -> OPENAI
                key.startsWith("AIza") -> GEMINI
                else -> null
            }
        }
    }
}

/**
 * AI 活跃度档位（RISK-1：控制整体调用频率）。
 *
 * - LOW：默认 10 RPM，每账号 2 条/日，人设更新 10 账号/7 天
 * - MEDIUM：默认 30 RPM，每账号 4 条/日，人设更新 20 账号/3 天
 * - HIGH：默认 60 RPM，每账号 8 条/日，人设更新 40 账号/3 天
 *
 * IMPL-47：[personaUpdateBatchSize] 与 [personaUpdatePeriodDays] 按档位缩放，
 * 避免 LOW/HIGH 档位下人设更新工作量相同。
 *
 * #95：[personaUpdatePeriodDays] 由原 LOW=14 / MEDIUM=7 / HIGH=3 缩短为 LOW=7 /
 * MEDIUM=3 / HIGH=3。WorkManager 的 PeriodicWorkRequest 在 Doze / 省电模式下会被
 * 大幅推迟，LOW 档原 14 天周期实际执行可能延后到 16-18 天，人设演进近乎停滞。
 * 缩短后即使被推迟数天，仍能在可接受窗口内触发更新。
 * 配套 setInitialDelay / setExpedited 由 core-scheduler 的 WorkerPolicies.kt 处理。
 *
 * #287：[profilePeriodHours] 与 [displayLabel] 此前散落在 WorkerKeys.kt 的 `when`
 * 与 SettingsScreen.kt 的扩展函数中，新增档位需同步修改多处。现收敛到枚举自身。
 *
 * @param profilePeriodHours UserProfileWorker 周期（小时），按档位缩放：LOW=96 / MEDIUM=48 / HIGH=24。
 * @param displayLabel UI 展示标签。
 */
enum class AiActivityLevel(
    val id: String,
    val rpmLimit: Int,
    val dailyPostsPerAccount: Int,
    val personaUpdateBatchSize: Int,
    val personaUpdatePeriodDays: Int,
    val profilePeriodHours: Long,
    val displayLabel: String,
) {
    LOW(
        id = "low",
        rpmLimit = 10,
        dailyPostsPerAccount = 2,
        personaUpdateBatchSize = 10,
        personaUpdatePeriodDays = 7,
        profilePeriodHours = 96L,
        displayLabel = "低 (LOW)",
    ),
    MEDIUM(
        id = "medium",
        rpmLimit = 30,
        dailyPostsPerAccount = 4,
        personaUpdateBatchSize = 20,
        personaUpdatePeriodDays = 3,
        profilePeriodHours = 48L,
        displayLabel = "中 (MEDIUM)",
    ),
    HIGH(
        id = "high",
        rpmLimit = 60,
        dailyPostsPerAccount = 8,
        personaUpdateBatchSize = 40,
        personaUpdatePeriodDays = 3,
        profilePeriodHours = 24L,
        displayLabel = "高 (HIGH)",
    );

    companion object {
        fun fromId(id: String?): AiActivityLevel? = values().find { it.id == id }
    }
}
