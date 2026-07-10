package com.trae.social.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 全屏图片查看器。
 *
 * - 双指缩放（detectTransformGestures）：1x - 5x，带平移边界钳制
 * - 双击切换 1x / 3x
 * - 单击关闭
 * - 顶部关闭按钮
 * - 背景黑色
 *
 * IMPL-34：原实现使用 transformable，无平移边界约束，图片可被拖出视口。
 * 改用 detectTransformGestures 并在 [onSizeChanged] 取得视口尺寸后将 offset
 * 钳制到 [-halfDelta, +halfDelta] 范围内。
 *
 * @param imageUri 图片 URI（已转换为 file:///android_asset/... 或 http(s)://...）
 * @param imageLoader 信息流专用 ImageLoader（含 SVG 解码）
 * @param onDismiss 关闭回调
 */
@Composable
fun FullScreenImage(
    imageUri: String,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // 缩放与位移状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // 视口像素尺寸，用于计算平移边界
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    // #24：大图查看器进出场过渡。
    // 初始 targetState=true 触发 scaleIn+fadeIn 进入；关闭时置 false，
    // 待退场动画完成（currentState 回到 false）后再回调 onDismiss 移除弹层。
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(visibleState.currentState) {
        if (!visibleState.currentState && !visibleState.targetState) {
            onDismiss()
        }
    }
    val requestDismiss = { visibleState.targetState = false }

    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = scaleIn(animationSpec = tween(durationMillis = 250), initialScale = 0.9f) +
                fadeIn(animationSpec = tween(durationMillis = 250)),
            exit = scaleOut(animationSpec = tween(durationMillis = 200), targetScale = 0.9f) +
                fadeOut(animationSpec = tween(durationMillis = 200)),
            label = "fullScreenImage",
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val request = remember(imageUri, context) {
                    ImageRequest.Builder(context)
                        .data(imageUri)
                        .crossfade(true)
                        .build()
                }

                AsyncImage(
                    model = request,
                    imageLoader = imageLoader,
                    contentDescription = "大图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewport = it }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { requestDismiss() },
                                onDoubleTap = {
                                    // 双击切换 1x / 3x
                                    if (scale > 1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = DOUBLE_TAP_SCALE
                                        offset = clampOffset(Offset.Zero, scale, viewport)
                                    }
                                },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                scale = newScale
                                if (newScale > 1f) {
                                    offset = clampOffset(offset + pan, newScale, viewport)
                                } else {
                                    offset = Offset.Zero
                                }
                            }
                        },
                )

                // 关闭按钮
                IconButton(
                    onClick = requestDismiss,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/**
 * 将平移偏移钳制到视口允许范围内，避免图片被拖出可见区域。
 *
 * 放大后图片宽高为 viewport * scale，可平移的最大距离为 (scale - 1) / 2 * viewport。
 */
private fun clampOffset(raw: Offset, scale: Float, viewport: IntSize): Offset {
    if (scale <= 1f) return Offset.Zero
    val maxX = (viewport.width * (scale - 1f)) / 2f
    val maxY = (viewport.height * (scale - 1f)) / 2f
    return Offset(
        x = raw.x.coerceIn(-maxX, maxX),
        y = raw.y.coerceIn(-maxY, maxY),
    )
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 3f
