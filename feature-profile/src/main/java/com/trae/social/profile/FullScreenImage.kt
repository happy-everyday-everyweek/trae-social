package com.trae.social.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
 * 个人主页全屏大图查看器（#8）。
 *
 * - HorizontalPager 左右切换媒体列表
 * - 双指缩放（1x-5x）+ 双击切换 1x/3x
 * - 单击关闭
 * - 顶部关闭按钮
 * - 背景黑色
 *
 * feature-profile 不依赖 feature-feed，故在此模块内独立实现，
 * 实现参考 feature-feed/FullScreenImage 与 feature-timeline/FullScreenImage。
 *
 * @param images 图片 URI 列表
 * @param initialIndex 初始展示下标
 * @param imageLoader 个人主页专用 ImageLoader（含 SVG 解码）
 * @param onDismiss 关闭回调
 */
@Composable
fun ProfileFullScreenImage(
    images: List<String>,
    initialIndex: Int,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
) {
    // 禁止在组合期调用 onDismiss（副作用），改为 LaunchedEffect 在挂起期执行
    val isEmpty = images.isEmpty()
    LaunchedEffect(isEmpty) {
        if (isEmpty) onDismiss()
    }
    if (isEmpty) return

    val safeIndex = initialIndex.coerceIn(0, images.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeIndex) { images.size }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableImage(
                    imageUri = images[page],
                    imageLoader = imageLoader,
                    onTap = onDismiss,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 关闭按钮
            IconButton(
                onClick = onDismiss,
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

/**
 * 可缩放图片：双指缩放（1x-5x）+ 双击在 1x/3x 间切换 + 单击关闭。
 *
 * detectTransformGestures 仅在多指或缩放时消费拖拽，
 * 单指横向拖拽透传给 HorizontalPager 完成翻页，
 * 避免与 Pager 手势冲突（参考 feature-timeline 的修正实现）。
 */
@Composable
private fun ZoomableImage(
    imageUri: String,
    imageLoader: ImageLoader,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // 视口像素尺寸，用于计算平移边界
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
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
                    // 缩放变化或已放大时才消费平移，避免拦截 Pager 的单指翻页
                    if (newScale != scale || newScale > 1f) {
                        scale = newScale
                        if (newScale > 1f) {
                            offset = clampOffset(offset + pan, newScale, viewport)
                        } else {
                            offset = Offset.Zero
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val context = LocalContext.current
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
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
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
