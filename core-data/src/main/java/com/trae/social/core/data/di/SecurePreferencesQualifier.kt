package com.trae.social.core.data.di

import javax.inject.Qualifier

/**
 * 标识 EncryptedSharedPreferences 提供的加密 SharedPreferences 实例。
 * 用于与其它 SharedPreferences 绑定区分。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecurePreferences
