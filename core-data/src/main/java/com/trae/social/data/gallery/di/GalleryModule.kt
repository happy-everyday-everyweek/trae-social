package com.trae.social.data.gallery.di

import com.trae.social.data.gallery.ImageUsagePort
import com.trae.social.data.gallery.InMemoryImageUsagePort
import com.trae.social.data.gallery.LocalImageGallery
import com.trae.social.data.gallery.LocalImageGalleryImpl
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
 * - [ImageUsagePort] -> [InMemoryImageUsagePort]（默认实现，Task 3 完成 Room
 *   ImageUsageDao 后应替换该绑定）
 * - 提供 kotlinx [Json] 单例，配置为忽略未知字段、容错宽松。
 *
 * 注意：[ImageUsagePort] 的默认绑定可能与 Task 3 后续的 Room 实现产生冲突，
 * 届时需删除下方 [bindImageUsagePort] 并由 core-data 的 Room 模块提供绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GalleryModule {

    @Binds
    @Singleton
    abstract fun bindLocalImageGallery(impl: LocalImageGalleryImpl): LocalImageGallery

    @Binds
    @Singleton
    abstract fun bindImageUsagePort(impl: InMemoryImageUsagePort): ImageUsagePort

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
