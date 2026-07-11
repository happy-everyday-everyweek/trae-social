package com.trae.social.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距 Token 集合（4dp baseline grid）。
 *
 * 统一全应用留白节奏，避免各页面硬编码 dp 导致间距不规整。
 * 通过 [LocalSocialSpacing] 暴露，可在 [SocialTheme] 中覆写。
 */
@Immutable
data class SocialSpacing(
    val xs: Dp = 4.dp,    // 紧凑间距
    val sm: Dp = 8.dp,    // 元素内间距
    val md: Dp = 12.dp,   // 元素间间距
    val lg: Dp = 16.dp,   // 卡片内边距 / 屏幕水平边距
    val xl: Dp = 24.dp,   // 区块间距
    val xxl: Dp = 32.dp,  // 页面级留白
)

/**
 * 通过 CompositionLocal 暴露当前间距 Token，供深层组件直接读取。
 *
 * 默认提供 [SocialSpacing] 默认值，未在 [SocialTheme] 中包裹时仍可用。
 */
val LocalSocialSpacing = staticCompositionLocalOf<SocialSpacing> { SocialSpacing() }
