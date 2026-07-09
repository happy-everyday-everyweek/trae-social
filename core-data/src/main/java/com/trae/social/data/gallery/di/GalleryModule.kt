package com.trae.social.data.gallery.di

import com.trae.social.data.gallery.ImageUsagePort
import com.trae.social.data.gallery.LocalImageGallery
import com.trae.social.data.gallery.LocalImageGalleryImpl
import com.trae.social.data.gallery.RoomImageUsagePort
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * 图库相关 Hilt 模块。
 *
 * 绑定：
 * - [LocalImageGallery] -> [LocalImageGalleryImpl]
 * - [ImageUsagePort] -> [RoomImageUsagePort]（Room 持久化实现，配图去重 30 天窗口跨进程重启有效）
 * - 提供 kotlinx [Json] 单例，配置为忽略未知字段、容错宽松。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GalleryModule {

    @Binds
    @Singleton
    abstract fun bindLocalImageGallery(impl: LocalImageGalleryImpl): LocalImageGallery

    @Binds
    @Singleton
    abstract fun bindImageUsagePort(impl: RoomImageUsagePort): ImageUsagePort

    companion object {

        @Provides
        @Singleton
        @GalleryJson
        fun provideGalleryJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}
