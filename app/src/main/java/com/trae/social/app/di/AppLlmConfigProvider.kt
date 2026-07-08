package com.trae.social.app.di

import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.llm.LlmConfigProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LlmConfigProvider] 的 app 模块实现。
 *
 * 桥接 core-data 的 [ConfigRepository]（异步、按需读取）与 core-llm 的
 * [LlmConfigProvider]（suspend、供 LlmProviderRegistry 调用）。
 *
 * IMPL-44：core-llm 通过 api 依赖 core-data，统一使用 [LlmProvider] 枚举，
 * 不再需要两个枚举间的手动映射。
 *
 * IMPL-14：所有方法为 suspend，不再使用 runBlocking。
 * 调用方（LlmProviderRegistry.getClient / AuthInterceptor）在协程或 OkHttp 线程中调用。
 */
@Singleton
class AppLlmConfigProvider @Inject constructor(
    private val configRepository: ConfigRepository,
) : LlmConfigProvider {

    override suspend fun getApiKey(provider: LlmProvider): String? =
        configRepository.getApiKey(provider)

    override suspend fun getBaseUrl(provider: LlmProvider): String? =
        configRepository.getBaseUrl(provider)

    override suspend fun getModel(provider: LlmProvider): String? =
        configRepository.getModelName(provider)

    override suspend fun getDefaultProvider(): LlmProvider {
        return configRepository.getDefaultProvider() ?: LlmProvider.OPENAI
    }
}
