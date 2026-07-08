package com.trae.social.llm

import com.trae.social.core.data.config.LlmProvider
import com.trae.social.llm.anthropic.AnthropicApi
import com.trae.social.llm.anthropic.AnthropicClient
import com.trae.social.llm.gemini.GeminiApi
import com.trae.social.llm.gemini.GeminiClient
import com.trae.social.llm.openai.OpenAiApi
import com.trae.social.llm.openai.OpenAiClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * LLM 提供商注册中心：按需懒创建并缓存各 [LlmClient] 实例。
 *
 * - [getClient] 按 [LlmProvider] 返回对应客户端，首次调用时创建；
 * - [getDefaultClient] 通过 [LlmConfigProvider] 获取用户配置的默认提供商；
 * - [invalidateCache] 清空缓存实例（切换默认提供商或 API Key / Base URL / 模型变更后调用）。
 *
 * Base URL 与模型均从 [LlmConfigProvider] 读取：
 * - Base URL 未配置时使用 @Named Retrofit（默认 Base URL）；
 * - Base URL 自定义时基于共享 OkHttpClient 新建 Retrofit。
 *
 * 兼容 OpenAI 协议的第三方端点通过 [LlmProvider.CUSTOM] 接入。
 *
 * IMPL-14：[getClient] / [getDefaultClient] 为 suspend，避免主线程 runBlocking 导致 ANR。
 * 使用 [Mutex] 替代 @Synchronized 保证线程安全。
 */
@Singleton
class LlmProviderRegistry @Inject constructor(
    @Named("openai") private val openAiRetrofit: Retrofit,
    @Named("anthropic") private val anthropicRetrofit: Retrofit,
    @Named("gemini") private val geminiRetrofit: Retrofit,
    private val okHttpClient: OkHttpClient,
    private val configProvider: LlmConfigProvider,
    private val json: Json,
) {

    private val clients = ConcurrentHashMap<LlmProvider, LlmClient>()
    private val mutex = Mutex()

    private val defaultOpenAiApi: OpenAiApi by lazy {
        openAiRetrofit.create(OpenAiApi::class.java)
    }
    private val defaultAnthropicApi: AnthropicApi by lazy {
        anthropicRetrofit.create(AnthropicApi::class.java)
    }
    private val defaultGeminiApi: GeminiApi by lazy {
        geminiRetrofit.create(GeminiApi::class.java)
    }

    /**
     * 获取指定提供商的客户端，不存在则懒创建并缓存。
     */
    suspend fun getClient(provider: LlmProvider): LlmClient {
        clients[provider]?.let { return it }
        return mutex.withLock {
            // Double-check after acquiring lock
            clients[provider]?.let { return it }
            val client = createClient(provider)
            clients[provider] = client
            client
        }
    }

    /**
     * 获取用户配置的默认提供商对应的客户端。
     */
    suspend fun getDefaultClient(): LlmClient = getClient(configProvider.getDefaultProvider())

    /**
     * 清空所有缓存的客户端实例。
     *
     * 在以下场景应调用：
     * - 用户切换默认提供商；
     * - API Key / Base URL / 模型名变更。
     *
     * P2 修复：获取 [mutex] 后再 clear，避免与 [getClient] 内的 createClient 产生
     * TOCTOU 竞态（clear 后 createClient 仍可能将旧配置的 client 写回缓存）。
     */
    suspend fun invalidateCache() {
        mutex.withLock { clients.clear() }
    }

    private suspend fun createClient(provider: LlmProvider): LlmClient {
        val model = configProvider.getModel(provider) ?: defaultModel(provider)
        val customBaseUrl = configProvider.getBaseUrl(provider)

        return when (provider) {
            LlmProvider.OPENAI, LlmProvider.CUSTOM -> {
                val api = if (customBaseUrl != null) {
                    buildRetrofit(customBaseUrl).create(OpenAiApi::class.java)
                } else {
                    defaultOpenAiApi
                }
                OpenAiClient(api, model, provider, json)
            }
            LlmProvider.ANTHROPIC -> {
                val api = if (customBaseUrl != null) {
                    buildRetrofit(customBaseUrl).create(AnthropicApi::class.java)
                } else {
                    defaultAnthropicApi
                }
                AnthropicClient(api, model, json)
            }
            LlmProvider.GEMINI -> {
                val api = if (customBaseUrl != null) {
                    buildRetrofit(customBaseUrl).create(GeminiApi::class.java)
                } else {
                    defaultGeminiApi
                }
                GeminiClient(api, model, json)
            }
        }
    }

    private fun defaultModel(provider: LlmProvider): String = when (provider) {
        LlmProvider.OPENAI, LlmProvider.CUSTOM -> DefaultModels.OPENAI
        LlmProvider.ANTHROPIC -> DefaultModels.ANTHROPIC
        LlmProvider.GEMINI -> DefaultModels.GEMINI
    }

    private fun buildRetrofit(baseUrl: String): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl.ensureTrailingSlash())
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"
}
