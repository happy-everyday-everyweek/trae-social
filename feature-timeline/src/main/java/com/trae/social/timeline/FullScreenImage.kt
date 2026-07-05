package com.trae.social.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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
    if (items.isEmpty()) {
        onDismiss()
        return
    }

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
 * 缩放与翻页互不冲突：双指手势由 transformable 处理，单指横向拖拽交给 HorizontalPager。
 */
@Composable
private fun ZoomableImage(
    imageUrl: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        scale = newScale
        offset = if (newScale > 1f) {
            offset + panChange
        } else {
            Offset.Zero
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_SCALE
                        }
                    },
                )
            }
            .transformable(transformState),
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

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 3f
