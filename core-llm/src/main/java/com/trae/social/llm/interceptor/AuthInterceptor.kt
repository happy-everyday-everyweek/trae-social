package com.trae.social.llm.interceptor

import com.trae.social.llm.LlmConfigProvider
import com.trae.social.llm.LlmHttp
import com.trae.social.core.data.config.LlmProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 按提供商注入鉴权信息的拦截器。
 *
 * - OpenAI / CUSTOM：`Authorization: Bearer <key>`
 * - Anthropic：`x-api-key: <key>` + `anthropic-version: 2023-06-01`
 * - Gemini：query 参数 `?key=<key>`
 *
 * 提供商类型通过请求头 [LlmHttp.PROVIDER_HEADER] 标记（由各 Client 注入），
 * 拦截器读取后移除该内部头，避免发送到网络。
 *
 * IMPL-14：[LlmConfigProvider.getApiKey] 为 suspend，此处用 runBlocking 调用。
 * OkHttp 拦截器运行在调度线程而非主线程，runBlocking 不影响 UI。
 *
 * 防御性约束：本拦截器为应用拦截器链的最外层（Auth -> Retry -> Logging），
 * 任何非 [IOException] 的 Throwable（如缺 Key 时曾经的 IllegalStateException、
 * EncryptedSharedPreferences 并发访问抛出的 RuntimeException 等）若逸出拦截器链，
 * 都会被 OkHttp 的 AsyncCall 在调用 onFailure 之后重新抛出，导致 "OkHttp Dispatcher"
 * 线程出现未捕获异常而触发全局崩溃处理器终止进程（闪退）。故此处统一将非 IOException
 * 转为 IOException，确保失败以可恢复方式传递给调用方，而非崩溃。
 */
class AuthInterceptor(
    private val configProvider: LlmConfigProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            val original = chain.request()
            val providerHeader = original.header(LlmHttp.PROVIDER_HEADER)
            val provider = parseProvider(providerHeader)

            val key = runBlocking { configProvider.getApiKey(provider) }

            val builder = original.newBuilder().removeHeader(LlmHttp.PROVIDER_HEADER)

            if (key.isNullOrBlank()) {
                // IMPL-27：缺少 API Key 时抛异常而非静默放行，
                // 让调用方明确区分"Key 未配置"与"网络问题"。
                // 使用 IOException：非 IOException 会被 OkHttp AsyncCall 重抛导致闪退。
                throw IOException("API key not configured for $provider")
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
            chain.proceed(authenticated)
        } catch (e: IOException) {
            // IOException（含 429 的 RateLimitedException）原样向上传递，
            // 供各 Worker 按类型精确捕获。
            throw e
        } catch (t: Throwable) {
            // 兜底：将任何非 IOException（如 EncryptedSharedPreferences 并发访问异常、
            // runBlocking 内部异常等）转为 IOException，避免被 OkHttp AsyncCall 重抛
            // 导致 "OkHttp Dispatcher" 线程未捕获异常闪退。
            throw IOException("AuthInterceptor failure: ${t.message}", t)
        }
    }

    private fun parseProvider(header: String?): LlmProvider {
        if (header.isNullOrBlank()) return LlmProvider.CUSTOM
        return runCatching { LlmProvider.valueOf(header.uppercase()) }.getOrDefault(LlmProvider.CUSTOM)
    }
}
