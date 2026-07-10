package com.trae.social.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 圆角 token 集合（#32）。
 *
 * 对齐 iOS 圆角节奏，全项目圆角统一取自 token，避免各处自定义 12/16/20dp 混用：
 * - [small] 8dp：小控件（标签、小图）
 * - [medium] 12dp：卡片、图片（默认）
 * - [large] 16dp：弹层、大卡片
 * - [extraLarge] 20dp：全屏弹层、对话框
 */
@Immutable
data class SocialShapes(
    val small: Shape,
    val medium: Shape,
    val large: Shape,
    val extraLarge: Shape,
)

/**
 * 默认圆角样式，对齐 iOS 圆角规范。
 */
val DefaultSocialShapes = SocialShapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/**
 * 通过 CompositionLocal 暴露当前圆角样式。
 */
val LocalSocialShapes = staticCompositionLocalOf<SocialShapes> {
    DefaultSocialShapes
}

/**
 * 将 [SocialShapes] 映射为 Material3 [Shapes]，便于复用 Material 组件默认圆角。
 */
internal fun SocialShapes.toMaterialShapes(): Shapes = Shapes(
    extraSmall = small,
    small = small,
    medium = medium,
    large = large,
    extraLarge = extraLarge,
)
