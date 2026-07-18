package com.trae.social.designsystem.image

import android.content.Context
import coil.ImageLoader
import coil.decode.SvgDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 共享的 SVG ImageLoader 限定符。
 *
 * 历史上 `feature-feed` 与 `feature-profile` 各自重复声明 `@FeedImageLoader` 与
 * `@ProfileImageLoader` 两个限定符，且对应 `@Provides` 函数体逐字一致。这里收敛为
 * 单一限定符 `@SvgImageLoader`，避免配置漂移（issue #221）。
 *
 * 注册含 [SvgDecoder] 的 [ImageLoader]，用于加载 assets 中的 SVG 头像与媒体图。
 * 通过限定符与 app 模块的默认 ImageLoader 区分。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SvgImageLoader

/**
 * 共享的 SVG ImageLoader Hilt 模块。
 *
 * 提供带 SVG 解码的 [ImageLoader] 单例，供 TweetCard / FullScreenImage /
 * ProfileScreen / FollowListScreen 等通过 `AsyncImage(imageLoader = ...)` 注入使用。
 *
 * 如未来不同 feature 需要差异化配置（如自定义磁盘缓存策略、不同 crossfade 时长），
 * 再拆分为各 feature 私有 Module；当前阶段共享以避免重复。
 */
@Module
@InstallIn(SingletonComponent::class)
object SvgImageLoaderModule {

    @Provides
    @Singleton
    @SvgImageLoader
    fun provideSvgImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
}
