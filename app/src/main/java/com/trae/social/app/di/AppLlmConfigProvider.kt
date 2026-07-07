package com.trae.social.app.di

import com.trae.social.core.data.config.LlmProvider as DataLlmProvider
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.llm.LlmConfigProvider
import com.trae.social.llm.LlmProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LlmConfigProvider] 的 app 模块实现。
 *
 * 桥接 core-data 的 [ConfigRepository]（异步、按需读取）与 core-llm 的
 * [LlmConfigProvider]（suspend、供 LlmProviderRegistry 调用）。
 *
 * 注意：core-llm 的 [LlmProvider] 与 core-data 的 [DataLlmProvider] 为两个独立枚举，
 * 此处负责两者间的映射。
 *
 * IMPL-14：所有方法为 suspend，不再使用 runBlocking。
 * 调用方（LlmProviderRegistry.getClient / AuthInterceptor）在协程或 OkHttp 线程中调用。
 */
@Singleton
class AppLlmConfigProvider @Inject constructor(
    private val configRepository: ConfigRepository,
) : LlmConfigProvider {

    override suspend fun getApiKey(provider: LlmProvider): String? =
        configRepository.getApiKey(provider.toData())

    override suspend fun getBaseUrl(provider: LlmProvider): String? =
        configRepository.getBaseUrl(provider.toData())

    override suspend fun getModel(provider: LlmProvider): String? =
        configRepository.getModelName(provider.toData())

    override suspend fun getDefaultProvider(): LlmProvider {
        val dataProvider = configRepository.getDefaultProvider()
            ?: DataLlmProvider.OPENAI
        return dataProvider.toLlm()
    }

    private fun LlmProvider.toData(): DataLlmProvider = when (this) {
        LlmProvider.OPENAI -> DataLlmProvider.OPENAI
        LlmProvider.ANTHROPIC -> DataLlmProvider.ANTHROPIC
        LlmProvider.GEMINI -> DataLlmProvider.GEMINI
        LlmProvider.CUSTOM -> DataLlmProvider.CUSTOM
    }

    private fun DataLlmProvider.toLlm(): LlmProvider = when (this) {
        DataLlmProvider.OPENAI -> LlmProvider.OPENAI
        DataLlmProvider.ANTHROPIC -> LlmProvider.ANTHROPIC
        DataLlmProvider.GEMINI -> LlmProvider.GEMINI
        DataLlmProvider.CUSTOM -> LlmProvider.CUSTOM
    }
}
