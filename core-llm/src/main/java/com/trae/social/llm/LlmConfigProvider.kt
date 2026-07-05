package com.trae.social.llm

/**
 * LLM 配置提供者抽象。
 *
 * 由 app 模块注入实现（基于 ConfigRepository / DataStore），
 * core-llm 模块仅依赖该接口，避免与 core-data 形成循环依赖。
 */
interface LlmConfigProvider {

    /** 获取指定提供商的 API Key，未配置时返回 null。 */
    fun getApiKey(provider: LlmProvider): String?

    /** 获取指定提供商的自定义 Base URL，未配置时返回 null（使用默认值）。 */
    fun getBaseUrl(provider: LlmProvider): String?

    /** 获取指定提供商使用的模型名，未配置时返回 null（使用默认值）。 */
    fun getModel(provider: LlmProvider): String?

    /** 获取用户当前的默认提供商。 */
    fun getDefaultProvider(): LlmProvider
}

/**
 * 各提供商的默认模型名，当 [LlmConfigProvider.getModel] 返回 null 时使用。
 */
object DefaultModels {
    const val OPENAI = "gpt-4o-mini"
    const val ANTHROPIC = "claude-3-5-sonnet-20240620"
    const val GEMINI = "gemini-1.5-flash"
    const val CUSTOM = "gpt-4o-mini"
}
