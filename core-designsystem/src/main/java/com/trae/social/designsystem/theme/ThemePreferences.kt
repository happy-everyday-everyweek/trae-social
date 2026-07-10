package com.trae.social.designsystem.theme

import android.content.Context
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

    /**
     * 从 SharedPreferences 加载已持久化的主题模式，应在 Activity onCreate 早期调用。
     */
    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ordinal = prefs.getInt(KEY_THEME_MODE, ThemeMode.SYSTEM.ordinal)
        themeMode = ThemeMode.values().getOrElse(ordinal) { ThemeMode.SYSTEM }
    }

    /**
     * 写入主题模式并更新可观察状态，触发依赖该状态的重组。
     */
    fun setThemeMode(context: Context, mode: ThemeMode) {
        themeMode = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, mode.ordinal)
            .apply()
    }

    /**
     * 依据当前偏好与系统深色状态计算是否使用深色配色。
     */
    fun isDarkTheme(systemInDark: Boolean): Boolean = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemInDark
    }
}
