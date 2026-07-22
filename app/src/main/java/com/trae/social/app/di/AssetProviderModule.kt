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
 * 图库资源领域 Hilt 绑定 Module（#217 拆分）。
 *
 * 仅负责 [AssetProvider] → [AssetProviderImpl] 绑定（assets 实际由 app 打包）。
 * LLM 相关绑定（[com.trae.social.llm.EndpointConfigProvider] /
 * [com.trae.social.core.data.repository.LlmCacheInvalidator]）已迁至
 * [AppLlmModule]，避免本 Module 名称暗示的"图库资源"职责与实际混装的 LLM 绑定混淆。
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
}
