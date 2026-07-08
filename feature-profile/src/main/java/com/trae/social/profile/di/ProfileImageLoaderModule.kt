package com.trae.social.profile.di

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
 * 提供带 SvgDecoder 的 ImageLoader（IMPL-43：feature-profile 缺 coil-svg，头像 SVG 无法解码）。
 *
 * feature-feed 已自建 FeedImageLoaderModule 注册 SvgDecoder，但该限定符不可跨模块复用，
 * 故 feature-profile 内独立提供。头像资源为 assets 中的 SVG。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProfileImageLoader

@Module
@InstallIn(SingletonComponent::class)
object ProfileImageLoaderModule {

    @Provides
    @Singleton
    @ProfileImageLoader
    fun provideProfileImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
}
