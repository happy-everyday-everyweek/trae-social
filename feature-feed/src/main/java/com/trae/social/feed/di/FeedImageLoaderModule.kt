package com.trae.social.feed.di

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
 * 信息流专用 ImageLoader 限定符。
 *
 * 注册含 SvgDecoder 的 ImageLoader，用于加载 assets 中的 SVG 头像与媒体图。
 * 通过限定符区分，避免与 app 模块默认 ImageLoader 冲突。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FeedImageLoader

/**
 * 信息流图片加载 Hilt 模块。
 *
 * 提供带 SVG 解码的 ImageLoader 单例，供 TweetCard / FullScreenImage 通过
 * AsyncImage(imageLoader = ...) 注入使用。
 */
@Module
@InstallIn(SingletonComponent::class)
object FeedImageLoaderModule {

    @Provides
    @Singleton
    @FeedImageLoader
    fun provideFeedImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
