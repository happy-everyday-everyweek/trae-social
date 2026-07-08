package com.trae.social.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest

/**
 * 全屏大图浏览器。
 *
 * - HorizontalPager 左右切换同日图片
 * - 双指缩放（1x-5x）+ 双击切换 1x/3x
 * - 顶部：日期标签 + 关闭按钮
 * - 底部：完整推文文本（若超过 1 行）
 * - 背景黑色
 *
 * @param items 同日图片列表
 * @param initialIndex 初始展示的图片下标
 * @param dateLabel 顶部展示的日期标签
 * @param onDismiss 关闭回调
 */
@Composable
fun FullScreenImageViewer(
    items: List<TimelineItem>,
    initialIndex: Int,
    dateLabel: String,
    onDismiss: () -> Unit,
) {
    // IMPL-34：禁止在组合期调用 onDismiss（副作用），改为 LaunchedEffect 在挂起期执行
    val isEmpty = items.isEmpty()
    LaunchedEffect(isEmpty) {
        if (isEmpty) onDismiss()
    }
    if (isEmpty) return

    val safeIndex = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeIndex) { items.size }
    val imageLoader = rememberSvgImageLoader()

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
                val item = items[page]
                ZoomableImage(
                    imageUrl = mediaPathToCoilUrl(item.mediaPath),
                    imageLoader = imageLoader,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 顶部：日期标签 + 关闭按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 4.dp)
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Text(
                    text = dateLabel,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                    )
                }
            }

            // 底部：完整推文文本（当前页）
            val currentPage = items.getOrNull(pagerState.currentPage)
            val caption = currentPage?.fullText.orEmpty()
            if (caption.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    Text(
                        text = caption,
                        color = Color.White,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 可缩放图片：双指缩放（1x-5x）+ 双击在 1x/3x 间切换。
 *
 * IMPL-34：原实现使用 transformable，与 HorizontalPager 单指翻页手势冲突，
 * 且无平移边界约束（图片可被拖出视口）。改用 detectTransformGestures，
 * 仅响应多指手势（scale != 1f 才消费拖拽），并在 [onSizeChanged] 取得视口尺寸后
 * 将 offset 钳制到 [-halfDelta, +halfDelta] 范围内，避免图片飘出视口。
 */
@Composable
private fun ZoomableImage(
    imageUrl: String,
    imageLoader: ImageLoader,
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
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_SCALE
                            // 双击放大后重新钳制偏移
                            offset = clampOffset(Offset.Zero, scale, viewport)
                        }
                    },
                )
            }
            // detectTransformGestures 仅在多指或缩放时消费拖拽，
            // 单指横向拖拽透传给 HorizontalPager 完成翻页
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
        val request = remember(imageUrl, context) {
            ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(true)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            imageLoader = imageLoader,
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
