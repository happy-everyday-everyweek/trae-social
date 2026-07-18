package com.trae.social.app.di

import android.content.Context
import com.trae.social.core.data.repository.LlmCacheInvalidator
import com.trae.social.data.gallery.AssetProvider
import com.trae.social.llm.EndpointConfigProvider
import com.trae.social.llm.EndpointRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AssetProvider] 的 Android 实现。
 *
 * 通过 [Context.assets] 访问 APK 内的 assets 资源，桥接 core-data 中
 * 图库模块与本地的 SVG 配图（assets/gallery/）。
 *
 * 路径约定与 Android AssetManager 一致：使用相对 assets 根的路径，
 * 例如 "gallery/index.json"。
 */
@Singleton
class AssetProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AssetProvider {

    override fun listAssets(path: String): List<String> {
        return context.assets.list(path)?.toList() ?: emptyList()
    }

    override fun openAsset(path: String): InputStream {
        return context.assets.open(path)
    }
}

/**
 * app 模块 Hilt 绑定集合（#151 重构后）。
 *
 * - [AssetProvider] → [AssetProviderImpl]（assets 实际由 app 打包）
 * - [EndpointConfigProvider] → [AppEndpointConfigProvider]（桥接 ConfigRepository）
 * - [LlmCacheInvalidator] → 桥接 [EndpointRegistry.invalidateCache]
 *   （取代旧 LlmProviderRegistry.invalidateCache 绑定）
 *
 * 旧 [com.trae.social.app.di.AppLlmConfigProvider]（提供
 * [com.trae.social.llm.LlmConfigProvider] 绑定）已随 #151 重构移除。
 */
@Module
@InstallIn(SingletonComponent::class)
object AssetProviderModule {

    @Provides
    @Singleton
    fun provideAssetProvider(impl: AssetProviderImpl): AssetProvider = impl

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
