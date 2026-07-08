package com.trae.social.app.di

import android.content.Context
import com.trae.social.data.gallery.AssetProvider
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
 * 绑定 [AssetProvider] 至 [AssetProviderImpl]。
 *
 * 该绑定位于 app 模块，因为 assets 实际由 app 打包；core-data 仅依赖接口。
 */
@Module
@InstallIn(SingletonComponent::class)
object AssetProviderModule {

    @Provides
    @Singleton
    fun provideAssetProvider(impl: AssetProviderImpl): AssetProvider = impl

    @Provides
    @Singleton
    fun provideLlmConfigProvider(impl: AppLlmConfigProvider): com.trae.social.llm.LlmConfigProvider = impl

    // P2 修复：提供 LlmCacheInvalidator 绑定，桥接 core-data 接口与 core-llm LlmProviderRegistry。
    @Provides
    @Singleton
    fun provideLlmCacheInvalidator(
        registry: com.trae.social.llm.LlmProviderRegistry,
    ): com.trae.social.core.data.repository.LlmCacheInvalidator =
        com.trae.social.core.data.repository.LlmCacheInvalidator { registry.invalidateCache() }
}
