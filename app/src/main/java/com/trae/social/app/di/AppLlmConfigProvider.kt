package com.trae.social.app.di

import com.trae.social.core.data.config.LlmProvider as DataLlmProvider
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.llm.LlmConfigProvider
import com.trae.social.llm.LlmProvider
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LlmConfigProvider] 的 app 模块实现。
 *
 * 桥接 core-data 的 [ConfigRepository]（异步、按需读取）与 core-llm 的
 * [LlmConfigProvider]（同步、供 OkHttp 拦截器调用）。
 *
 * 注意：core-llm 的 [LlmProvider] 与 core-data 的 [DataLlmProvider] 为两个独立枚举，
 * 此处负责两者间的映射。
 *
 * 使用 [runBlocking] 将 suspend 调用转为同步：
 * - API Key / Base URL / Model 读取 EncryptedSharedPreferences，开销极低；
 * - 默认提供商读取 DataStore，首次可能有少量 IO，后续由 DataStore 缓存。
 * 拦截器在每次请求时调用本类，runBlocking 仅阻塞 OkHttp 线程，不影响主线程。
 */
@Singleton
class AppLlmConfigProvider @Inject constructor(
    private val configRepository: ConfigRepository,
) : LlmConfigProvider {

    override fun getApiKey(provider: LlmProvider): String? = runBlocking {
        configRepository.getApiKey(provider.toData())
    }

    override fun getBaseUrl(provider: LlmProvider): String? = runBlocking {
        configRepository.getBaseUrl(provider.toData())
    }

    override fun getModel(provider: LlmProvider): String? = runBlocking {
        configRepository.getModelName(provider.toData())
    }

    override fun getDefaultProvider(): LlmProvider = runBlocking {
        val dataProvider = configRepository.getDefaultProvider()
            ?: DataLlmProvider.OPENAI
        dataProvider.toLlm()
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
