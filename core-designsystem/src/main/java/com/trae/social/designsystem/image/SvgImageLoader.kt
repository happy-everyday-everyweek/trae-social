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
 * 带 SvgDecoder 的 ImageLoader 共享限定符（#221）。
 *
 * 抽取自 feature-feed / feature-profile 中重复的 FeedImageLoaderModule 与
 * ProfileImageLoaderModule——两个模块函数体完全一致，仅限定符名不同，导致
 * 同一份 SvgDecoder + crossfade 配置在两个 feature 各注册一次。
 *
 * 现统一由 core-designsystem 提供单例 ImageLoader，跨 feature 复用，避免：
 * - 重复构建 ImageLoader（重复磁盘缓存、重复线程池）
 * - 后续如需调整 SvgDecoder 配置需多处改动
 *
 * 使用方通过 `@SvgImageLoader` 限定符注入即可。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SvgImageLoader

/**
 * 共享 SVG ImageLoader Hilt 模块。
 *
 * 提供带 SvgDecoder 的 ImageLoader 单例，供 TweetCard / Avatar /
 * FullScreenImage 等通过 `@SvgImageLoader` 注入使用，加载 assets 中的
 * SVG 头像与媒体图。
 */
@Module
@InstallIn(SingletonComponent::class)
object SvgImageLoaderModule {

    @Provides
    @Singleton
    @SvgImageLoader
    fun provideSvgImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
