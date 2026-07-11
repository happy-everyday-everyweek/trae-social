package com.trae.social.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Apple 风格设计系统的色彩 Token 集合。
 *
 * 参照 iOS HIG 的 systemColor 体系，提供明/暗两套配色。
 * 所有颜色均为 sRGB 色值，与 iOS 26 视觉保持一致。
 */
@Immutable
data class SocialColors(
    // 主品牌色：iOS systemBlue
    val systemBlue: Color,
    val systemRed: Color,
    val systemGreen: Color,
    val systemOrange: Color,
    val systemPurple: Color,

    // 背景层级
    val systemBackground: Color,
    val secondaryBackground: Color,
    val tertiaryBackground: Color,

    // 文本与分隔
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,

    // 磨砂玻璃降级使用的半透明表面色
    val surface: Color,
)

/**
 * 明色配色：对应 iOS Light Mode。
 */
val LightSocialColors = SocialColors(
    systemBlue = Color(0xFF007AFF),
    systemRed = Color(0xFFFF3B30),
    systemGreen = Color(0xFF34C759),
    systemOrange = Color(0xFFFF9500),
    systemPurple = Color(0xFFAF52DE),
    systemBackground = Color(0xFFFFFFFF),
    secondaryBackground = Color(0xFFF2F2F7),
    tertiaryBackground = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x99000000),
    tertiaryLabel = Color(0x4D000000),
    separator = Color(0x5C000000),
    surface = Color(0xFFF2F2F7),
)

/**
 * 深色配色：对应 iOS Dark Mode。
 */
val DarkSocialColors = SocialColors(
    systemBlue = Color(0xFF0A84FF),
    systemRed = Color(0xFFFF453A),
    systemGreen = Color(0xFF30D158),
    systemOrange = Color(0xFFFF9F0A),
    systemPurple = Color(0xFFBF5AF2),
    systemBackground = Color(0xFF000000),
    secondaryBackground = Color(0xFF1C1C1E),
    tertiaryBackground = Color(0xFF2C2C2E),
    // #29：正文由纯白微调为 #F2F2F7（接近白但略柔），与纯黑背景对比约 18:1，仍达 WCAG AAA，
    // 减轻长时间阅读的眼疲劳；避免纯黑+纯白的极限对比。
    label = Color(0xFFF2F2F7),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    // #27：分隔线由 0x66(40%) 降至 0x38(22%)，深色模式下更柔和，避免在纯黑背景上偏重
    separator = Color(0x38545458),
    surface = Color(0xFF1C1C1E),
)

/**
 * 通过 CompositionLocal 暴露当前配色，供深层组件直接读取。
 */
val LocalSocialColors = staticCompositionLocalOf<SocialColors> {
    error("SocialColors 未提供，请在 SocialTheme 中包裹内容")
}

/**
 * 读取当前主题提供的配色方案。
 *
 * 始终从 [LocalSocialColors] 获取，以保证与 [SocialTheme] 中传入的 darkTheme 覆写保持一致，
 * 避免直接读取系统深色模式导致手动切换主题时配色不生效。
 */
@Composable
fun socialColors(): SocialColors = LocalSocialColors.current
