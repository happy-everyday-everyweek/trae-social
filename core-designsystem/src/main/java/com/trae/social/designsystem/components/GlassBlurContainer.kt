package com.trae.social.designsystem.components

import android.app.ActivityManager
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.theme.LocalSocialColors

/**
 * 磨砂玻璃性能分级，决定模糊半径基线（RISK-6 低端机性能降级）。
 *
 * - [HIGH]：高端机，模糊半径 20dp
 * - [MID]：中端机，模糊半径 10dp
 * - [LOW]：低端机，模糊半径 0dp，强制降级为纯色半透明
 */
enum class GlassBlurTier {
    HIGH,
    MID,
    LOW,
}

/**
 * 滚动状态标记：true 时模糊半径减半，降低滚动期 GPU 开销。
 *
 * 由外层滚动容器通过 [provideIsScrolling] 提供，磨砂玻璃组件自动读取。
 */
val LocalIsScrolling = staticCompositionLocalOf { false }

/**
 * 计算当前设备的磨砂玻璃性能分级。
 *
 * 判定依据：
 * 1. [ActivityManager.isLowRamDevice] 为 true → [GlassBlurTier.LOW]（强制降级）
 * 2. API < 31（RenderEffect 不可用）→ [GlassBlurTier.MID]（名义 10dp，但实际降级为纯色）
 * 3. API >= 31 且内存充裕（memoryClass >= 256）→ [GlassBlurTier.HIGH]
 * 4. 其余 → [GlassBlurTier.MID]
 */
@Composable
fun rememberGlassBlurTier(): GlassBlurTier {
    val context = LocalContext.current
    return remember(context) {
        context.glassBlurTier()
    }
}

private fun Context.glassBlurTier(): GlassBlurTier {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return GlassBlurTier.MID
    if (am.isLowRamDevice) return GlassBlurTier.LOW
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return GlassBlurTier.MID
    return if (am.memoryClass >= 256) GlassBlurTier.HIGH else GlassBlurTier.MID
}

private fun GlassBlurTier.baseRadius(): Dp = when (this) {
    GlassBlurTier.HIGH -> 20.dp
    GlassBlurTier.MID -> 10.dp
    GlassBlurTier.LOW -> 0.dp
}

/**
 * 磨砂玻璃容器：模拟 iOS 26 风格的半透明毛玻璃效果。
 *
 * IMPL-33：原实现 `Modifier.blur(radius).background(tint)` 把模糊作用于整个节点
 * （含子内容），导致 Tab 图标/文案也被模糊；且 `Modifier.blur` 不会模糊"背后"内容，
 * 半透明色叠加后仍只是着色条，非真正毛玻璃。
 *
 * 现策略（双图层）：
 * - 背景层：API 31+ 且半径 > 0 时用 `Modifier.graphicsLayer { renderEffect =
 *   RenderEffect.createBlurEffect(...) }` 真实模糊背景层渲染（含半透明 tint 的
 *   软化边缘与任何叠加纹理），并叠加半透明 tint；API < 31 或低端机降级为纯色半透明
 * - 内容层：保持锐利，不受模糊影响
 * - 滚动中（[LocalIsScrolling] = true）：模糊半径减半，缓解滚动掉帧
 *
 * @param modifier 外部修饰符
 * @param cornerRadius 圆角半径，默认 0dp（底栏场景可传较大圆角）
 * @param tint 叠加色调，默认使用系统 surface 半透明
 * @param content 容器内容
 */
@Composable
fun GlassBlurContainer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    tint: Color? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalSocialColors.current
    val tier = rememberGlassBlurTier()
    val isScrolling = LocalIsScrolling.current

    val baseRadius = tier.baseRadius()
    // 滚动时半径减半
    val effectiveRadius = if (isScrolling) baseRadius / 2 else baseRadius
    // 是否可使用真实模糊：API31+ 且半径 > 0
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && effectiveRadius > 0.dp

    val glassTint = tint ?: colors.surface.copy(alpha = if (canBlur) 0.55f else 0.85f)

    val shape = if (cornerRadius > 0.dp) RoundedCornerShape(cornerRadius) else null

    val clipModifier = if (shape != null) Modifier.clip(shape) else Modifier

    Box(modifier = modifier.then(clipModifier)) {
        // 背景层：模糊 + 半透明色
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (canBlur) {
                        // IMPL-33：RenderEffect 真实模糊背景层渲染（px 半径）
                        Modifier.graphicsLayer {
                            val radiusPx = with(density) { effectiveRadius.toPx() }
                            renderEffect = RenderEffect.createBlurEffect(
                                radiusPx,
                                radiusPx,
                                Shader.TileMode.CLAMP,
                            ).asComposeRenderEffect()
                        }
                    } else {
                        Modifier
                    },
                )
                .background(glassTint),
        )
        // 内容层：保持锐利
        content()
    }
}

/**
 * 为子树提供滚动状态，影响内部 [GlassBlurContainer] 的模糊半径。
 *
 * 典型用法：在滚动容器外层调用 [provideIsScrolling]，传入滚动派生状态。
 */
@Composable
fun provideIsScrolling(
    isScrolling: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIsScrolling provides isScrolling) {
        content()
    }
}
