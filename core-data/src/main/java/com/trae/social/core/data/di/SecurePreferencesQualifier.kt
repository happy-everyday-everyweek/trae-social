package com.trae.social.core.data.di

import android.content.SharedPreferences
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 标识 EncryptedSharedPreferences 提供的加密 SharedPreferences 实例。
 * 用于与其它 SharedPreferences 绑定区分。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecurePreferences

/**
 * #301：加密 SharedPreferences 构建结果持有者。
 *
 * 持有同一份 [prefs] 实例与 [degraded] 标志，保证 [SharedPreferences] 绑定与
 * [SecurePrefsAvailability] 绑定来自同一次构建，状态判定一致。
 */
@Singleton
class SecurePrefsHolder internal constructor(
    val prefs: SharedPreferences,
    val degraded: Boolean,
)

/**
 * #301：加密存储可用性信号。
 *
 * [degraded]=true 表示 EncryptedSharedPreferences 创建失败，已降级为纯内存存储，
 * 密钥不会持久化（进程结束即丢失）。UI 应据此提示用户重新输入密钥或排查 Keystore。
 */
@Singleton
class SecurePrefsAvailability(val degraded: Boolean) {
    /** 加密存储可用（未降级）。 */
    val isAvailable: Boolean get() = !degraded
}
