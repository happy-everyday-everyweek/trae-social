package com.trae.social.designsystem.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 主题模式枚举：浅色 / 深色 / 跟随系统。
 */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * 主题偏好管理：通过 SharedPreferences 持久化用户选择的主题模式，
 * 并以 Compose 可观察状态暴露，供 SocialTheme 读取以覆写系统深色模式。
 *
 * 使用方式：
 * - Activity onCreate 早期调用 [initialize] 加载已持久化的偏好；
 * - SocialTheme 通过 [isDarkTheme] 取得最终是否深色；
 * - 设置页通过 [setThemeMode] 切换模式，状态变更会触发 SocialTheme 重组。
 */
object ThemePreferences {
    private const val PREFS_NAME = "social_theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    // 应用级可观察状态，变更后读取处会重组
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    // 缓存 SharedPreferences 实例，避免每次切换主题时重新获取
    private var prefs: SharedPreferences? = null

    /**
     * 从 SharedPreferences 加载已持久化的主题模式，应在 Activity onCreate 早期调用。
     */
    fun initialize(context: Context) {
        ensurePrefs(context)
        themeMode = try {
            ThemeMode.valueOf(prefs!!.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }
    }

    /**
     * 写入主题模式并更新可观察状态，触发依赖该状态的重组。
     *
     * #209：原实现忽略 [context] 参数，仅依赖成员变量 [prefs]。若进程被杀后通过其他入口
     * （如 BootReceiver 触发的 WorkManager 任务、或未来新增的 Activity/EntryPoint）重启
     * 而未经过 [initialize]，[prefs] 为 null，[setThemeMode] 只更新内存中的 [themeMode]
     * 而不写盘，下次启动偏好丢失。此处先调用 [ensurePrefs] 兜底初始化。
     */
    fun setThemeMode(context: Context, mode: ThemeMode) {
        themeMode = mode
        ensurePrefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    /**
     * 获取 [prefs]，若未初始化则用 [context] 兜底初始化。
     *
     * 使用 applicationContext 避免持有 Activity 实例导致泄漏。
     *
     * 加 [Synchronized] 防止并发调用导致重复初始化（object 单例，[prefs] 为 var）。
     */
    @Synchronized
    private fun ensurePrefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }

    /**
     * 依据当前偏好与系统深色状态计算是否使用深色配色。
     */
    fun isDarkTheme(systemInDark: Boolean): Boolean = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemInDark
    }
}
