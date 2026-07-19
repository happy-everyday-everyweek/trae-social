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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.theme.LocalSocialColors

/**
 * 高光宽度（px）。固定 300px 与原行为保持一致，足够在窄组件上形成明显扫光，
 * 在宽组件上则由 [shimmerBrush] 内部的 widthPx 动态扩展动画范围。
 */
private const val HIGHLIGHT_WIDTH_PX = 300f

/**
 * Shimmer 占位组件，用于图片与列表项的加载态。
 *
 * 使用主题色（secondaryBackground / tertiaryBackground）构建渐变高光扫光动画，
 * 自动适配明暗主题。
 *
 * #215：动画范围基于组件实测宽度动态计算（widthPx + [HIGHLIGHT_WIDTH_PX]），
 * 避免宽屏/横屏大尺寸占位上扫光无法横跨整个组件、Restart 跳回 0 时右侧消失左侧突现。
 *
 * @param modifier 外部修饰符
 * @param cornerRadius 占位圆角，默认 8dp
 */
@Composable
fun LoadingShimmer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    // #215：跟踪组件实测宽度，未测量时 shimmerBrush 返回静态渐变
    var widthPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .onGloballyPositioned { coords ->
                if (coords.size.width > 0) widthPx = coords.size.width
            }
            .background(shimmerBrush(widthPx)),
    )
}

/**
 * 构造一个水平扫光的渐变 Brush，可用于任意背景。
 *
 * #215：[widthPx] 为组件实测宽度。宽度为 0（首帧尚未测量）时返回静态渐变，
 * 避免用错误的默认范围启动动画、测量后跳变到真实范围。
 * 宽度 > 0 时动画 targetValue = widthPx + [HIGHLIGHT_WIDTH_PX]，使高光能完整扫过
 * 整个组件后再 Restart 回到 0，避免宽组件右侧覆盖不全、Restart 时跳变。
 *
 * @param widthPx 调用方组件的实测宽度（px），0 表示尚未测量
 */
@Composable
fun shimmerBrush(widthPx: Int = 0): Brush {
    val colors = LocalSocialColors.current
    val gradientColors = listOf(
        colors.secondaryBackground,
        colors.tertiaryBackground,
        colors.secondaryBackground,
    )
    // #215：宽度为 0（尚未测量）时返回静态渐变，避免首帧用错误范围动画、测量后跳变
    if (widthPx <= 0) {
        return Brush.linearGradient(
            colors = gradientColors,
            start = Offset(0f, 0f),
            end = Offset(HIGHLIGHT_WIDTH_PX, 0f),
        )
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = widthPx.toFloat() + HIGHLIGHT_WIDTH_PX,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    // 基色为次级背景，高光为三级背景（暗色模式下三级更亮，明色模式下三级更亮）
    return Brush.linearGradient(
        colors = gradientColors,
        start = Offset(translateAnim - HIGHLIGHT_WIDTH_PX, 0f),
        end = Offset(translateAnim, 0f),
    )
}

/**
 * 修饰符扩展：为任意组件叠加 shimmer 扫光背景。
 *
 * #215：在 composed 块中跟踪组件实测宽度并传入 [shimmerBrush]，使扫光范围自适应。
 */
fun Modifier.shimmer(cornerRadius: Dp = 8.dp): Modifier = composed {
    var widthPx by remember { mutableIntStateOf(0) }
    this
        .clip(RoundedCornerShape(cornerRadius))
        .onGloballyPositioned { coords ->
            if (coords.size.width > 0) widthPx = coords.size.width
        }
        .background(shimmerBrush(widthPx))
}
