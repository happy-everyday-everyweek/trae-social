package com.trae.social.llm.di

import com.trae.social.llm.DefaultEndpointRegistry
import com.trae.social.llm.DefaultRulesetEngine
import com.trae.social.llm.EndpointRegistry
import com.trae.social.llm.RulesetEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * core-llm 模块的 Hilt 依赖注入配置（#151 重构）。
 *
 * 重构后不再需要全局共享 OkHttpClient / Retrofit / Json——各 SDK client
 * （[com.trae.social.llm.openai.OpenAiCompatibleClient] /
 * [com.trae.social.llm.anthropic.AnthropicCompatibleClient]）内部自行构造
 * OkHttpClient 并直接调用官方 Java SDK，鉴权头与重试均由 SDK 内置处理，
 * 旧 [com.trae.social.llm.interceptor.AuthInterceptor] /
 * [com.trae.social.llm.interceptor.RetryInterceptor] /
 * [com.trae.social.llm.interceptor.LoggingInterceptor] 已移除。
 *
 * 本模块仅做接口到实现的绑定：
 * - [EndpointConfigProvider] → app 模块的 [com.trae.social.app.di.AppEndpointConfigProvider]
 *   （由 app 模块的 [com.trae.social.app.di.AssetProviderModule] 提供）
 * - [RulesetEngine] → [DefaultRulesetEngine]
 * - [EndpointRegistry] → [DefaultEndpointRegistry]（#306：DIP，原 `EndpointRegistry`
 *   具体类已升级为接口，依赖该接口的组件可注入 stub/fake 做单元测试）
 *
 * 注意：[EndpointConfigProvider] 的具体实现绑定声明位于 app 模块的 AssetProviderModule，
 * 因为其实现类 [com.trae.social.app.di.AppEndpointConfigProvider] 依赖 app 模块的
 * [com.trae.social.core.data.repository.ConfigRepository]。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {

    @Binds
    @Singleton
    abstract fun bindRulesetEngine(impl: DefaultRulesetEngine): RulesetEngine

    @Binds
    @Singleton
    abstract fun bindEndpointRegistry(impl: DefaultEndpointRegistry): EndpointRegistry
}
