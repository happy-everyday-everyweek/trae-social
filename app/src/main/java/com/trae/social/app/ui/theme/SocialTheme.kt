package com.trae.social.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 临时主题色定义；Task 2 将迁移至 core-designsystem 模块统一管理。
private val LightPrimary = Color(0xFF3B6FE6)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightSurface = Color(0xFFFDFBFF)
private val LightOnSurface = Color(0xFF1A1C1E)

private val DarkPrimary = Color(0xFFAAC7FF)
private val DarkOnPrimary = Color(0xFF002F72)
private val DarkSurface = Color(0xFF121316)
private val DarkOnSurface = Color(0xFFE3E2E6)

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    surface = LightSurface,
    onSurface = LightOnSurface,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
)

/**
 * 应用 Compose 主题。
 *
 * 注意：此为骨架阶段的最简实现，Task 2 将被 core-designsystem 模块的
 * SocialTheme 替换（包含完整设计令牌、Typography、Shape 等）。
 */
@Composable
fun SocialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
