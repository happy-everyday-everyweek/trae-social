package com.trae.social.core.data.gallery.di

import javax.inject.Qualifier

/**
 * 限定图库模块专用的 [kotlinx.serialization.json.Json] 实例。
 *
 * 使用限定符避免与 core-data 其它模块（如 Task 3 网络层）可能提供的
 * Json 绑定产生 Hilt 重复绑定冲突。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
annotation class GalleryJson
