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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
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
 *
 * #178：使用 [compositionLocalOf] 而非 `staticCompositionLocalOf`。该值在滚动期间通过
 * `snapshotFlow { isScrollInProgress }` 高频变化，且 `provideIsScrolling` 包裹了整个
 * Scaffold（含 NavHost 与所有页面）。`staticCompositionLocalOf` 在值变化时会强制重组
 * Provider 包裹的整个子树（不会按 reader 精确触发），导致每次滚动状态切换时所有页面
 * 都被重组。改用 [compositionLocalOf] 后只有读取该值的 [GlassBlurContainer] 会重组。
 */
val LocalIsScrolling = compositionLocalOf { false }

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
 * #2 修复：原实现仅在 `graphicsLayer { renderEffect = ... }` 上叠加纯色 tint，
 * RenderEffect 只会模糊该 Box 自身绘制的实心色（模糊实心色无可见变化），
 * 无法模糊底栏背后的 Feed 内容，与 iOS 26 毛玻璃视觉目标存在本质差距。
 *
 * 现策略（三图层）：
 * - 背景层：当外层提供 [backgroundLayer]（由 `rememberGraphicsLayer()` 捕获的
 *   背后内容）时，将其平移对齐后经 `CompositingStrategy.Offscreen` +
 *   `RenderEffect.createBlurEffect` 真实模糊绘制，实现真正的"模糊背后内容"。
 *   无背景图层时回退为纯色半透明（向后兼容）。
 * - 色调层：在模糊背景上叠加半透明 tint，控制玻璃质感透明度。
 * - 内容层：保持锐利，不受模糊影响。
 * - 滚动中（[LocalIsScrolling] = true）：模糊半径减半，缓解滚动掉帧。
 * - 低端机（[GlassBlurTier.LOW]）或 API < 31：降级为纯色半透明，无模糊。
 *
 * @param modifier 外部修饰符
 * @param cornerRadius 圆角半径，默认 0dp（底栏场景可传较大圆角）
 * @param tint 叠加色调，默认使用系统 surface 半透明
 * @param backgroundLayer 可选的已捕获内容图层，用于真正的背后内容模糊；
 *   由外层通过 `rememberGraphicsLayer()` + `Modifier.drawWithContent` 捕获并传入。
 * @param backgroundLayerOffsetY 背景图层的 Y 轴平移偏移（px），用于将内容中
 *   对应底栏位置的区域对齐到容器顶部；通常为 `barHeight - contentHeight`。
 * @param content 容器内容
 */
@Composable
fun GlassBlurContainer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    tint: Color? = null,
    backgroundLayer: GraphicsLayer? = null,
    backgroundLayerOffsetY: Float = 0f,
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
        // 背景层：真正的背后内容模糊（当提供背景图层且可模糊时）
        if (canBlur && backgroundLayer != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Offscreen 合成确保 renderEffect 作用于整个图层内容
                        compositingStrategy = CompositingStrategy.Offscreen
                        val radiusPx = with(density) { effectiveRadius.toPx() }
                        renderEffect = RenderEffect.createBlurEffect(
                            radiusPx,
                            radiusPx,
                            Shader.TileMode.DECAL,
                        ).asComposeRenderEffect()
                    }
                    .drawWithContent {
                        // 将捕获的内容图层平移对齐后裁剪到容器区域，
                        // 经外层 graphicsLayer 的 renderEffect 真实模糊，
                        // 实现"模糊背后内容"的视觉效果
                        clipRect {
                            translate(top = backgroundLayerOffsetY) {
                                drawLayer(backgroundLayer)
                            }
                        }
                    },
            )
        }
        // 色调叠加层：半透明 tint 控制玻璃质感透明度
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (canBlur && backgroundLayer == null) {
                        // 无背景图层时保留原有模糊色调（向后兼容）
                        Modifier.graphicsLayer {
                            val radiusPx = with(density) { effectiveRadius.toPx() }
                            renderEffect = RenderEffect.createBlurEffect(
                                radiusPx,
                                radiusPx,
                                Shader.TileMode.DECAL,
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
