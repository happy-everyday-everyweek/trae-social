package com.trae.social.feed

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
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
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 全屏图片查看器（#4 支持多图翻页）。
 *
 * - HorizontalPager 左右切换同一推文的多张图片
 * - 双指缩放（detectTransformGestures）：1x - 5x，带平移边界钳制
 * - 双击切换 1x / 3x
 * - 单击关闭
 * - 顶部关闭按钮
 * - 多图时底部居中展示 "当前页/总数" 页码指示器
 * - 背景黑色
 *
 * IMPL-34：原实现使用 transformable，无平移边界约束，图片可被拖出视口。
 * 改用 detectTransformGestures 并在 [onSizeChanged] 取得视口尺寸后将 offset
 * 钳制到 [-halfDelta, +halfDelta] 范围内。
 *
 * #4：由单图升级为多图翻页，缩放手势沿用 timeline 模块 ZoomableImage 的冲突规避方案
 * ——仅当缩放系数变化或已放大时才消费平移，单指横向拖拽透传给 HorizontalPager 完成翻页。
 *
 * @param imageUris 图片 URI 列表（已转换为 file:///android_asset/... 或 http(s)://...）
 * @param initialIndex 初始展示的图片下标
 * @param imageLoader 信息流专用 ImageLoader（含 SVG 解码）
 * @param onDismiss 关闭回调
 */
@Composable
fun FullScreenImage(
    imageUris: List<String>,
    initialIndex: Int,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
) {
    // 禁止在组合期调用 onDismiss（副作用），改为 LaunchedEffect 在挂起期执行
    val isEmpty = imageUris.isEmpty()
    LaunchedEffect(isEmpty) {
        if (isEmpty) onDismiss()
    }
    if (isEmpty) return

    val safeIndex = initialIndex.coerceIn(0, imageUris.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeIndex) { imageUris.size }
    val typography = LocalSocialTypography.current

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
                    imageUrl = imageUris[page],
                    imageLoader = imageLoader,
                    onDismiss = onDismiss,
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

            // 多图页码指示器 "当前页/总数"
            if (imageUris.size >= 2) {
                Text(
                    text = "${pagerState.currentPage + 1}/${imageUris.size}",
                    color = Color.White,
                    style = typography.caption2,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * 可缩放图片：双指缩放（1x-5x）+ 双击在 1x/3x 间切换 + 单击关闭。
 *
 * detectTransformGestures 仅在多指或缩放时消费拖拽，单指横向拖拽透传给 HorizontalPager 完成翻页。
 * 在 [onSizeChanged] 取得视口尺寸后将 offset 钳制到视口允许范围内，避免图片飘出视口。
 */
@Composable
private fun ZoomableImage(
    imageUrl: String,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 缩放与位移状态
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
                    onTap = { onDismiss() },
                    onDoubleTap = {
                        // 双击切换 1x / 3x
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
