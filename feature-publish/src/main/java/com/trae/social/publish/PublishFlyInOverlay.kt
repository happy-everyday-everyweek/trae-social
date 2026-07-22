package com.trae.social.publish

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trae.social.designsystem.theme.LocalReduceMotion
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 缩小飞入信息流动画覆盖层。
 *
 * #157：总时长压到 [PUBLISH_ANIM_DURATION_MS] = 280ms（原 700ms），缓动统一改为
 * [FastOutSlowInEasing]（原 EaseOutBack 弹性过冲）。发布是 deliberate 提交动作，
 * 应 crisp 而非 bouncy，弹性过冲与"确定、可靠"的产品人格不匹配。
 * 减弱动效下使用 [REDUCED_PUBLISH_ANIM_DURATION_MS]，移除位移/缩放仅保留淡入淡出。
 *
 * 三阶段（按比例分配）：
 * - 阶段1（0-PHASE_1_RATIO）：scale 1->0.6 + 上移至屏幕中上部；
 * - 阶段2（PHASE_1_RATIO-PHASE_2_RATIO）：继续缩小 + 淡出 + "发布成功"提示淡入；
 * - 阶段3（PHASE_2_RATIO-1）：成功提示淡出，随后回调 onPublished。
 *
 * 使用 [graphicsLayer] 应用变换。
 * RISK-8 降级：当 imagePath 为空时仅显示成功提示（fade + scale），不显示预览图。
 */
@Composable
internal fun PublishFlyInOverlay(
    visible: Boolean,
    imagePath: String?,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    // #157：减弱动效下使用更短时长，让用户尽快看到发布结果
    val durationMs = if (reduceMotion) REDUCED_PUBLISH_ANIM_DURATION_MS
        else PUBLISH_ANIM_DURATION_MS

    LaunchedEffect(visible, reduceMotion) {
        if (visible) {
            progress.snapTo(0f)
            // #157：进度驱动器用 tween + FastOutSlowInEasing（原 EaseOutBack 改为 crisp 缓动）
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMs,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            progress.snapTo(0f)
        }
    }

    // 不再要求 imagePath != null：降级模式下也显示成功提示
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(0)),
        exit = fadeOut(tween(0)),
        modifier = modifier,
    ) {
        val p = progress.value.coerceIn(0f, 1f)
        val typography = LocalSocialTypography.current
        // #157：所有缓动统一改为 FastOutSlowInEasing（原 EaseOutBack 弹性过冲已移除），
        // 成功提示与缩放均使用 crisp 缓动，与 deliberate 发布动作匹配
        val easing = FastOutSlowInEasing

        // #157：减弱动效下移除缩放动画（仅保留淡入淡出），符合 ReduceMotion.kt
        // 「移除 transform/位移类动画，仅保留 opacity 与 color 过渡」原则
        val scaleVal = if (reduceMotion) 1f
            else when {
                p < PHASE_1_RATIO -> {
                    val frac = easing.transform(p / PHASE_1_RATIO)
                    lerpUnclamped(1f, 0.6f, frac)
                }
                p < PHASE_2_RATIO -> {
                    val frac = easing.transform(
                        (p - PHASE_1_RATIO) / (PHASE_2_RATIO - PHASE_1_RATIO)
                    )
                    lerpUnclamped(0.6f, 0.15f, frac)
                }
                else -> 0.15f
            }
        // 图片透明度：阶段1保持 1，阶段2淡出至 0
        val alphaVal = when {
            p < PHASE_1_RATIO -> 1f
            p < PHASE_2_RATIO -> {
                val frac = (p - PHASE_1_RATIO) / (PHASE_2_RATIO - PHASE_1_RATIO)
                lerp(1f, 0f, frac)
            }
            else -> 0f
        }
        // #157：减弱动效下完全移除位移（图片居中淡出），符合 ReduceMotion.kt 原则；
        // 默认用 FastOutSlowInEasing（原 EaseOutBack 改为 crisp 缓动，避免弹性"弹过头再回落"）
        // review 第 5 轮修复：translationY 原用裸 px 值（400/-200），不同屏幕密度下位移距离不一致。
        // 改用 dp 转 px，保证视觉位移在各类密度设备上一致。
        val translationYVal = if (reduceMotion) 0f
            else lerpUnclamped(
                with(density) { 400.dp.toPx() },
                with(density) { (-200).dp.toPx() },
                FastOutSlowInEasing.transform(p),
            )

        // 成功提示：阶段2淡入，阶段3淡出
        val successAlpha = when {
            p < PHASE_1_RATIO -> 0f
            p < PHASE_2_RATIO -> {
                val frac = (p - PHASE_1_RATIO) / (PHASE_2_RATIO - PHASE_1_RATIO)
                lerp(0f, 1f, frac)
            }
            else -> {
                val frac = (p - PHASE_2_RATIO) / (1f - PHASE_2_RATIO)
                lerp(1f, 0f, frac)
            }
        }
        // #157：成功提示缩放也改用 FastOutSlowInEasing（原 EaseOutBack 移除）；
        // 减弱动效下保持 1f 不缩放
        val successScale = if (reduceMotion) 1f
            else when {
                p < PHASE_1_RATIO -> 0.85f
                p < PHASE_2_RATIO -> {
                    val frac = easing.transform(
                        (p - PHASE_1_RATIO) / (PHASE_2_RATIO - PHASE_1_RATIO)
                    )
                    lerpUnclamped(0.85f, 1f, frac)
                }
                else -> {
                    val frac = easing.transform(
                        (p - PHASE_2_RATIO) / (1f - PHASE_2_RATIO)
                    )
                    lerpUnclamped(1f, 0.8f, frac)
                }
            }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = maxOf(alphaVal, successAlpha) * 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            // 预览图（仅当有路径时显示）
            val path = imagePath
            if (path != null) {
                AsyncImage(
                    model = path,
                    contentDescription = "发布中",
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .graphicsLayer {
                            scaleX = scaleVal
                            scaleY = scaleVal
                            alpha = alphaVal
                            translationY = translationYVal
                        },
                )
            }
            // 成功提示：checkmark + 文本（降级模式下也显示，保证最小成功反馈）
            // 始终组合，通过 graphicsLayer alpha 控制可见性，避免 alpha 由 0 变 >0 时布局抖动
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = successAlpha
                    scaleX = successScale
                    scaleY = successScale
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "发布成功",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "发布成功",
                    color = Color.White,
                    style = typography.body,
                )
            }
        }
    }
}

/**
 * 线性插值（钳制 fraction 至 [0, 1]），适用于线性或不过冲的缓动。
 */
private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction.coerceIn(0f, 1f)

/**
 * 不钳制的线性插值：保留过冲值（>1），
 * 避免 coerceIn 截断而丢失效果。
 */
private fun lerpUnclamped(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

// #157：总时长从 700ms 压到 280ms（< 300ms UI 动画标准），缓动改为 FastOutSlowInEasing
internal const val PUBLISH_ANIM_DURATION_MS = 280
// #157：减弱动效下的更短时长，仅保留淡入淡出反馈
internal const val REDUCED_PUBLISH_ANIM_DURATION_MS = 150
private const val PHASE_1_RATIO = 3f / 7f
private const val PHASE_2_RATIO = 6f / 7f
