package com.trae.social.llm

import com.trae.social.core.data.config.LlmProvider

/**
 * LLM 配置提供者抽象。
 *
 * 由 app 模块注入实现（基于 ConfigRepository / DataStore），
 * core-llm 模块通过 api 依赖 core-data 复用 [LlmProvider] 枚举。
 *
 * IMPL-14：所有方法为 suspend，避免在主线程上 runBlocking 导致 ANR。
 * [com.trae.social.llm.interceptor.AuthInterceptor] 运行在 OkHttp 线程，
 * 可安全使用 runBlocking 调用这些方法。
 */
interface LlmConfigProvider {

    /** 获取指定提供商的 API Key，未配置时返回 null。 */
    suspend fun getApiKey(provider: LlmProvider): String?

    /** 获取指定提供商的自定义 Base URL，未配置时返回 null（使用默认值）。 */
    suspend fun getBaseUrl(provider: LlmProvider): String?

    /** 获取指定提供商使用的模型名，未配置时返回 null（使用默认值）。 */
    suspend fun getModel(provider: LlmProvider): String?

    /** 获取用户当前的默认提供商。 */
    suspend fun getDefaultProvider(): LlmProvider
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
