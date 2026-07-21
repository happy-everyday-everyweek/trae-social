package com.trae.social.app.di

import com.trae.social.core.data.repository.LlmCacheInvalidator
import com.trae.social.llm.EndpointConfigProvider
import com.trae.social.llm.EndpointRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * LLM 领域 Hilt 绑定 Module（#217 拆分自 [AssetProviderModule]）。
 *
 * - [EndpointConfigProvider] → [AppEndpointConfigProvider]（桥接 ConfigRepository）
 * - [LlmCacheInvalidator] → 桥接 [EndpointRegistry.invalidateCache]
 *   （取代旧 LlmProviderRegistry.invalidateCache 绑定）
 *
 * 拆分原因：原 [AssetProviderModule] 名称暗示仅负责图库资源领域，实际却混装了
 * 图库资源（AssetProvider）与 LLM 配置/缓存（EndpointConfigProvider /
 * LlmCacheInvalidator）三个不相关领域绑定，可读性与可维护性差。拆分后
 * 新增 LLM 绑定时可直接放入本 Module，按文件名查找 LLM 绑定也能命中。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppLlmModule {

    @Provides
    @Singleton
    fun provideEndpointConfigProvider(impl: AppEndpointConfigProvider): EndpointConfigProvider = impl

    /**
     * #151 重构后绑定到 [EndpointRegistry.invalidateCache]。
     *
     * feature-profile 的 ApiKeyViewModel 在端点 CRUD / API Key 变更后调用本 invalidator
     * 清空缓存的 SDK client 实例，使下次请求按新配置创建。
     */
    @Provides
    @Singleton
    fun provideLlmCacheInvalidator(
        registry: EndpointRegistry,
    ): LlmCacheInvalidator = LlmCacheInvalidator { registry.invalidateCache() }
}
