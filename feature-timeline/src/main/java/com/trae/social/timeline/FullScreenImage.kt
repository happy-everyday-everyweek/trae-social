package com.trae.social.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.rememberUpdatedState
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
import coil.request.ImageRequest

/**
 * 全屏大图浏览器。
 *
 * - HorizontalPager 左右切换同日图片
 * - 双指缩放（1x-5x）+ 双击切换 1x/3x
 * - 主 review 第 4 轮修复：自顶下滑关闭手势（仅 scale == 1f 时启用），拖拽时
 *   图片随手指下移并淡出，背景同步变透明，松手超过阈值则 dismiss
 * - 顶部：日期标签 + 关闭按钮
 * - 底部：完整推文文本（若超过 1 行）
 * - 背景黑色
 *
 * @param items 同日图片列表
 * @param initialIndex 初始展示的图片下标
 * @param dateLabel 顶部展示的日期标签
 * @param imageLoader 共享 [@SvgImageLoader] [ImageLoader]（由 [TimelineViewModel] 注入，
 *   主 review 第 1 轮 M3 修复：替代原 Composable 内 rememberSvgImageLoader() 本地构造）
 * @param onDismiss 关闭回调
 */
@Composable
fun FullScreenImageViewer(
    items: List<TimelineItem>,
    initialIndex: Int,
    dateLabel: String,
    imageLoader: ImageLoader,
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

    // 主 review 第 4 轮修复：自顶下滑关闭手势的拖拽进度（0f..1f）。
    // ZoomableImage 在 scale == 1f 时监听纵向下拖，更新此状态；
    // 父 Box 背景透明度据此渐变（1f -> 0f），实现"拖得越远背景越透"的视觉反馈。
    var dismissDragProgress by remember { mutableFloatStateOf(0f) }

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
                // 背景透明度随下滑进度衰减：1 - progress
                .background(Color.Black.copy(alpha = (1f - dismissDragProgress).coerceIn(0f, 1f))),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = items[page]
                ZoomableImage(
                    imageUrl = mediaPathToCoilUrl(item.mediaPath),
                    imageLoader = imageLoader,
                    // #187：透传 onDismiss，供 ZoomableImage 的 onTap 实现单击关闭
                    onDismiss = onDismiss,
                    // 主 review 第 4 轮修复：透传拖拽进度回调，父 Box 背景透明度据此渐变
                    onDismissDragProgress = { progress -> dismissDragProgress = progress },
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
 *
 * #187：补充 onTap 与 Feed/Profile 对齐——放大时单击先复位缩放与位移，
 * 未放大时单击关闭查看器，原先仅能靠右上角关闭按钮或系统返回键退出。
 *
 * 主 review 第 4 轮修复：补充自顶下滑关闭手势（仅 scale == 1f 时启用）。
 * detectVerticalDragGestures 只消费纵向拖拽，不拦截 HorizontalPager 的横向翻页；
 * scale > 1f 时不注册该手势（pointerInput(scale) 重启），改由 detectTransformGestures
 * 处理放大后的平移。拖拽时图片随手指下移并淡出，经 [onDismissDragProgress] 通知父
 * Box 同步调整背景透明度；松手累计位移超过 [DISMISS_DRAG_THRESHOLD_PX] 时调用
 * [onDismiss]，否则回弹到原位。
 */
@Composable
private fun ZoomableImage(
    imageUrl: String,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
    onDismissDragProgress: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // 视口像素尺寸，用于计算平移边界
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // #187：始终捕获最新的 onDismiss，避免 pointerInput(Unit) 不重启导致回调过期
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // 主 review 第 4 轮修复：始终捕获最新的进度回调，避免 pointerInput 重启时回调过期
    val currentOnDismissDragProgress by rememberUpdatedState(onDismissDragProgress)
    // 自顶下滑关闭手势的累计纵向位移（px），仅记录正向下方向
    var dismissDragY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            // 主 review 第 4 轮修复：自顶下滑关闭手势，仅未放大（scale <= 1f）时启用。
            // pointerInput(scale) 在 scale 变化时重启，确保放大后此手势不与
            // detectTransformGestures 的平移竞争。detectVerticalDragGestures 只消费纵向拖拽，
            // HorizontalPager 的横向翻页不受影响。
            .pointerInput(scale) {
                if (scale <= 1f) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dismissDragY = 0f
                            currentOnDismissDragProgress(0f)
                        },
                        onDragEnd = {
                            // 超过阈值则关闭，否则回弹（清零，让动画自然回到原位）
                            if (dismissDragY >= DISMISS_DRAG_THRESHOLD_PX) {
                                currentOnDismiss()
                            }
                            dismissDragY = 0f
                            currentOnDismissDragProgress(0f)
                        },
                        onDragCancel = {
                            dismissDragY = 0f
                            currentOnDismissDragProgress(0f)
                        },
                    ) { _, dragAmount ->
                        // 仅累计向下拖拽（dragAmount > 0 表示手指向下移动）；
                        // 向上拖拽时允许回退但不进入负值，避免图片上移
                        dismissDragY = (dismissDragY + dragAmount).coerceAtLeast(0f)
                        // 进度 = 累计位移 / 阈值，钳制到 0..1 用于透明度渐变
                        val progress = (dismissDragY / DISMISS_DRAG_THRESHOLD_PX)
                            .coerceIn(0f, 1f)
                        currentOnDismissDragProgress(progress)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    // #187：放大时单击先复位缩放与位移，未放大时才关闭查看器（与 Feed 一致）
                    onTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            currentOnDismiss()
                        }
                    },
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
        // 主 review 第 4 轮修复：下滑关闭手势期间，图片随手指下移并淡出。
        // progress = dismissDragY / DISMISS_DRAG_THRESHOLD_PX，钳制到 0..1
        val dismissProgress = (dismissDragY / DISMISS_DRAG_THRESHOLD_PX).coerceIn(0f, 1f)
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
                    translationY = offset.y + dismissDragY,
                    // 拖拽时图片透明度从 1 衰减到 0.3（不完全透明，保留可见反馈）
                    alpha = (1f - dismissProgress * 0.7f).coerceIn(0.3f, 1f),
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

/**
 * 主 review 第 4 轮修复：自顶下滑关闭手势的位移阈值（px）。
 *
 * 累计纵向下拖超过此值时松手即关闭查看器；否则回弹。约 1/3 屏幕高度（按 ~1080px
 * 视口估算），兼顾"易触发"与"不易误触"。
 */
private const val DISMISS_DRAG_THRESHOLD_PX = 360f
