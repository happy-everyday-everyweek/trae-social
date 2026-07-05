package com.trae.social.llm.interceptor

import com.trae.social.llm.LlmConfigProvider
import com.trae.social.llm.LlmHttp
import com.trae.social.llm.LlmProvider
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 按提供商注入鉴权信息的拦截器。
 *
 * - OpenAI / CUSTOM：`Authorization: Bearer <key>`
 * - Anthropic：`x-api-key: <key>` + `anthropic-version: 2023-06-01`
 * - Gemini：query 参数 `?key=<key>`
 *
 * 提供商类型通过请求头 [LlmHttp.PROVIDER_HEADER] 标记（由各 Client 注入），
 * 拦截器读取后移除该内部头，避免发送到网络。
 */
class AuthInterceptor(
    private val configProvider: LlmConfigProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val providerHeader = original.header(LlmHttp.PROVIDER_HEADER)
        val provider = parseProvider(providerHeader)

        val key = configProvider.getApiKey(provider)

        val builder = original.newBuilder().removeHeader(LlmHttp.PROVIDER_HEADER)

        if (key.isNullOrBlank()) {
            return chain.proceed(builder.build())
        }

        val authenticated = when (provider) {
            LlmProvider.OPENAI, LlmProvider.CUSTOM -> {
                builder.header("Authorization", "Bearer $key").build()
            }
            LlmProvider.ANTHROPIC -> {
                builder
                    .header("x-api-key", key)
                    .header("anthropic-version", LlmHttp.ANTHROPIC_VERSION)
                    .build()
            }
            LlmProvider.GEMINI -> {
                val newUrl = original.url.newBuilder()
                    .addQueryParameter("key", key)
                    .build()
                builder.url(newUrl).build()
            }
        }
        return chain.proceed(authenticated)
    }

    private fun parseProvider(header: String?): LlmProvider {
        if (header.isNullOrBlank()) return LlmProvider.CUSTOM
        return runCatching { LlmProvider.valueOf(header.uppercase()) }.getOrDefault(LlmProvider.CUSTOM)
    }
}
