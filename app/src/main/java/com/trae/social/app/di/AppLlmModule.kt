package com.trae.social.app.di

import com.trae.social.llm.EndpointConfigProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * LLM 领域 Hilt 绑定 Module（#217 拆分自 [AssetProviderModule]）。
 *
 * - [EndpointConfigProvider] → [AppEndpointConfigProvider]（桥接 ConfigRepository）
 *
 * 拆分原因：原 [AssetProviderModule] 名称暗示仅负责图库资源领域，实际却混装了
 * 图库资源（AssetProvider）与 LLM 配置（EndpointConfigProvider）两个不相关领域绑定，
 * 可读性与可维护性差。拆分后新增 LLM 绑定时可直接放入本 Module，按文件名查找
 * LLM 绑定也能命中。
 *
 * #288：旧 [com.trae.social.core.data.repository.LlmCacheInvalidator] 绑定已移除——
 * EndpointRegistry 订阅 [com.trae.social.core.data.repository.ConfigRepository.endpointChanges]
 * 后自动失效缓存，feature 模块无需再手动调 invalidateCache()。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppLlmModule {

    @Provides
    @Singleton
    fun provideEndpointConfigProvider(impl: AppEndpointConfigProvider): EndpointConfigProvider = impl
}
