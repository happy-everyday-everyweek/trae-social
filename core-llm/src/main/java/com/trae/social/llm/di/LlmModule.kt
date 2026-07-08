package com.trae.social.llm.di

import com.trae.social.llm.LlmConfigProvider
import com.trae.social.llm.LlmHttp
import com.trae.social.llm.interceptor.AuthInterceptor
import com.trae.social.llm.interceptor.LoggingInterceptor
import com.trae.social.llm.interceptor.RetryInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * core-llm 模块的 Hilt 依赖注入配置。
 *
 * 提供：
 * - 全局共享 [OkHttpClient]（含 Auth / Retry / Logging 拦截器链）；
 * - 按 provider 限定的 @Named [Retrofit] 实例（使用默认 Base URL）；
 * - [Json] 序列化器。
 *
 * IMPL-26：移除 HTTP 层 [com.trae.social.llm.interceptor.RateLimitInterceptor]，
 * 限流统一由 core-scheduler 的 [com.trae.social.core.scheduler.ratelimit.SchedulerRateLimiter]
 * 在调度入口处执行，避免双层限流导致 HIGH 档位仍被 30 RPM 封顶。
 *
 * IMPL-32：拦截器顺序 Auth → Retry → Logging，使 Logging 在 Retry 内部，
 * 每次重试尝试都会被记录，提升可观测性。
 *
 * 注意：[LlmConfigProvider] 需由 app 模块通过独立 Hilt Module 提供，
 * 否则编译期 Hilt 会报缺失绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // 请求体中所有非 null 字段（含默认值）都需序列化，避免 API 使用其自身默认值
        encodeDefaults = true
        // null 字段（如 response_format / system）不序列化
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        configProvider: LlmConfigProvider,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(LlmHttp.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(LlmHttp.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(LlmHttp.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // IMPL-32：Auth → Retry → Logging，Logging 在 Retry 内部记录每次尝试
        .addInterceptor(AuthInterceptor(configProvider))
        .addInterceptor(RetryInterceptor())
        .addInterceptor(LoggingInterceptor())
        .build()

    @Provides
    @Named("openai")
    @Singleton
    fun provideOpenAiRetrofit(client: OkHttpClient, json: Json): Retrofit =
        buildRetrofit(LlmHttp.OPENAI_BASE_URL, client, json)

    @Provides
    @Named("anthropic")
    @Singleton
    fun provideAnthropicRetrofit(client: OkHttpClient, json: Json): Retrofit =
        buildRetrofit(LlmHttp.ANTHROPIC_BASE_URL, client, json)

    @Provides
    @Named("gemini")
    @Singleton
    fun provideGeminiRetrofit(client: OkHttpClient, json: Json): Retrofit =
        buildRetrofit(LlmHttp.GEMINI_BASE_URL, client, json)

    private fun buildRetrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }
}
