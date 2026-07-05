package com.trae.social.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.theme.LocalSocialColors

/**
 * Shimmer 占位组件，用于图片与列表项的加载态。
 *
 * 使用主题色（secondaryBackground / tertiaryBackground）构建渐变高光扫光动画，
 * 自动适配明暗主题。
 *
 * @param modifier 外部修饰符
 * @param cornerRadius 占位圆角，默认 8dp
 */
@Composable
fun LoadingShimmer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(shimmerBrush()),
    )
}

/**
 * 构造一个水平扫光的渐变 Brush，可用于任意背景。
 */
@Composable
fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    val colors = LocalSocialColors.current
    // 基色为次级背景，高光为三级背景（暗色模式下三级更亮，明色模式下三级更亮）
    return Brush.linearGradient(
        colors = listOf(
            colors.secondaryBackground,
            colors.tertiaryBackground,
            colors.secondaryBackground,
        ),
        start = Offset(translateAnim - 300f, 0f),
        end = Offset(translateAnim, 0f),
    )
}

/**
 * 修饰符扩展：为任意组件叠加 shimmer 扫光背景。
 */
fun Modifier.shimmer(cornerRadius: Dp = 8.dp): Modifier = composed {
    this
        .clip(RoundedCornerShape(cornerRadius))
        .background(shimmerBrush())
}
