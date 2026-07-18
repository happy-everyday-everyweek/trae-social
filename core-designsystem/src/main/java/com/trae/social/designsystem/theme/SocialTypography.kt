package com.trae.social.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Apple 风格设计系统的字体样式 Token 集合。
 *
 * 字号与字重对齐 iOS SF Pro 文本样式（largeTitle ~ caption2）。
 * 字体回退链使用 [FontFamily.SansSerif]：在 Android 上回退到系统默认无衬线字体，
 * 中文场景下由系统默认中文字体承接，避免打包下载 SF Pro 增大 APK 体积。
 */
@Immutable
data class SocialTypography(
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val subheadline: TextStyle,
    val footnote: TextStyle,
    val caption1: TextStyle,
    val caption2: TextStyle,
)

/**
 * 默认字体族：系统 SansSerif，提供 SF 风格回退与中文系统字体回退。
 */
private val DefaultFontFamily: FontFamily = FontFamily.SansSerif

/**
 * 默认排版样式，对齐 iOS 文本样式规范。
 */
val DefaultSocialTypography = SocialTypography(
    largeTitle = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.37.sp,
    ),
    title1 = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.36.sp,
    ),
    title2 = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.35.sp,
    ),
    title3 = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.38.sp,
    ),
    headline = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.37.sp,
    ),
    body = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.37.sp,
    ),
    callout = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.31.sp,
    ),
    subheadline = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.24.sp,
    ),
    footnote = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.08.sp,
    ),
    caption1 = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.07.sp,
    ),
    caption2 = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.06.sp,
    ),
)

/**
 * 通过 CompositionLocal 暴露当前排版样式。
 *
 * #195：提供 [DefaultSocialTypography] 作为 default，使 `@Preview`、单测与非
 * `SocialTheme` 包裹的临时组合能正常渲染，与 `LocalSocialSpacing`/`LocalSocialShapes`
 * 行为一致。正式 UI 仍应通过 [SocialTheme] 提供排版。
 */
val LocalSocialTypography = staticCompositionLocalOf<SocialTypography> {
    DefaultSocialTypography
}
