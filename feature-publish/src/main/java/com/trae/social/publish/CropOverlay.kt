package com.trae.social.publish

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 图片预览 + 可调大小的裁剪矩形遮罩。
 *
 * 裁剪框以归一化坐标 [cropRect]（left/top/right/bottom ∈ [0,1]）表示，相对容器尺寸。
 * - 拖拽裁剪框内部：整体平移，钳制在容器内。
 * - 拖拽 4 个边角 handle：调整对应角，钳制最小尺寸 [MIN_CROP_RATIO] 并保持 left<right、top<bottom。
 * - 比例预设：由调用方在切换比例时重新居中计算 [cropRect]（见 [computeCenteredCropRect]），
 *   拖拽时不再强制维持比例，允许自由微调。
 *
 * IMPL-36：通过 [onSizeChanged] 拿到容器真实像素尺寸，供 [applyCropAndFilter] 按比例
 * 映射到源图坐标，取代原硬编码近似。
 */
@Composable
internal fun CropOverlay(
    bitmap: Bitmap,
    filter: FilterPreset,
    cropRect: Rect,
    onCropRectChange: (Rect) -> Unit,
    containerSize: IntSize,
    onContainerSizeChange: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val density = LocalDensity.current
    // #175：displayBitmap 由 filter + bitmap 派生，切换时显式回收旧 Bitmap。
    // 原实现 remember(filter, bitmap) { filter.apply(bitmap) } 丢弃旧引用仅靠 GC 回收，
    // 快速切换 5 个滤镜会瞬时累计 80MB+ 未释放 Bitmap。
    // ORIGINAL 返回源引用（不可回收），其余 preset 通过 createBitmap 产生新 Bitmap。
    var displayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val latestBitmap by rememberUpdatedState(bitmap)
    LaunchedEffect(filter, bitmap) {
        val old = displayBitmap
        // review 第 5 轮修复：filter.apply 在主线程做 createBitmap + Canvas.drawBitmap（源图
        // 最大 2048²），切换滤镜会卡顿 / ANR。移到 Dispatchers.Default。
        val new = withContext(Dispatchers.Default) { filter.apply(bitmap) }
        try {
            ensureActive()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // filter/bitmap 在后台 apply 期间变化，回收刚产生的 bitmap 避免泄漏
            if (new !== bitmap) new.recycle()
            throw e
        }
        displayBitmap = new
        if (old != null && old !== bitmap && old !== new) {
            // review 第 5 轮修复：推迟到下一帧再 recycle，避免与 RenderThread 仍在绘制 old 的
            // 帧并发触发 "Canvas: trying to use a recycled bitmap" 崩溃。
            // #322：原用 android.os.Handler.post 在协程内强行切回 Looper 队列，破坏结构化
            // 并发——LaunchedEffect 取消时 Handler runnable 仍会执行并 recycle 已被新流程
            // 复用的 bitmap。改用 withFrameNanos 挂起到下一帧，协程取消时回收逻辑也随之
            // 取消，语义与 Choreographer 一致（next vsync）。
            withFrameNanos { }
            old.recycle()
        }
    }
    // #175：组合离开时回收 displayBitmap（非源引用部分），避免内存泄漏
    DisposableEffect(Unit) {
        onDispose {
            val current = displayBitmap
            if (current != null && current !== latestBitmap) {
                current.recycle()
            }
        }
    }
    val currentDisplay = displayBitmap ?: bitmap

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.tertiaryBackground)
            .onSizeChanged { onContainerSizeChange(it) },
    ) {
        Image(
            bitmap = currentDisplay.asImageBitmap(),
            contentDescription = "编辑预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        if (containerSize != IntSize.Zero) {
            val cw = containerSize.width.toFloat()
            val ch = containerSize.height.toFloat()
            val leftPx = (cropRect.left * cw).roundToInt()
            val topPx = (cropRect.top * ch).roundToInt()
            val rightPx = (cropRect.right * cw).roundToInt()
            val bottomPx = (cropRect.bottom * ch).roundToInt()
            val cropWpx = (rightPx - leftPx).coerceAtLeast(1)
            val cropHpx = (bottomPx - topPx).coerceAtLeast(1)
            val cropWdp = with(density) { cropWpx.toDp() }
            val cropHdp = with(density) { cropHpx.toDp() }

            // 裁剪框边框 + 内部拖拽（整体平移）
            Box(
                modifier = Modifier
                    .offset { IntOffset(leftPx, topPx) }
                    .size(cropWdp, cropHdp)
                    .border(width = 2.dp, color = colors.systemBlue, shape = RoundedCornerShape(4.dp))
                    .pointerInput(containerSize) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            // 归一化位移，钳制使裁剪框不越出容器
                            val dxNorm = (drag.x / cw).coerceIn(-cropRect.left, 1f - cropRect.right)
                            val dyNorm = (drag.y / ch).coerceIn(-cropRect.top, 1f - cropRect.bottom)
                            onCropRectChange(cropRect.translate(dxNorm, dyNorm))
                        }
                    },
            )

            // 4 个边角拖拽 handle：分别调整 left/top、right/top、left/bottom、right/bottom
            CornerHandle(
                corner = Corner.TOP_LEFT,
                centerX = leftPx,
                centerY = topPx,
                containerSize = containerSize,
                cropRect = cropRect,
                onCropRectChange = onCropRectChange,
            )
            CornerHandle(
                corner = Corner.TOP_RIGHT,
                centerX = rightPx,
                centerY = topPx,
                containerSize = containerSize,
                cropRect = cropRect,
                onCropRectChange = onCropRectChange,
            )
            CornerHandle(
                corner = Corner.BOTTOM_LEFT,
                centerX = leftPx,
                centerY = bottomPx,
                containerSize = containerSize,
                cropRect = cropRect,
                onCropRectChange = onCropRectChange,
            )
            CornerHandle(
                corner = Corner.BOTTOM_RIGHT,
                centerX = rightPx,
                centerY = bottomPx,
                containerSize = containerSize,
                cropRect = cropRect,
                onCropRectChange = onCropRectChange,
            )
        }
    }
}

/** 边角标识，用于 [CornerHandle] 判断拖拽时调整哪两条边。 */
private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * 单个边角拖拽 handle：以 (centerX, centerY) 像素位置为中心绘制一个小方块，
 * 拖拽时按对应方向调整 [cropRect] 的边，保持最小尺寸与 left<right、top<bottom。
 */
@Composable
private fun BoxScope.CornerHandle(
    corner: Corner,
    centerX: Int,
    centerY: Int,
    containerSize: IntSize,
    cropRect: Rect,
    onCropRectChange: (Rect) -> Unit,
) {
    val colors = LocalSocialColors.current
    val density = LocalDensity.current
    val handleSize = 28.dp
    val halfPx = with(density) { (handleSize / 2).toPx() }.roundToInt()
    val cw = containerSize.width.toFloat()
    val ch = containerSize.height.toFloat()

    Box(
        modifier = Modifier
            .offset { IntOffset(centerX - halfPx, centerY - halfPx) }
            .size(handleSize)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.systemBlue)
            .border(width = 1.dp, color = colors.systemBackground, shape = RoundedCornerShape(4.dp))
            .pointerInput(containerSize, corner) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val dxNorm = drag.x / cw
                    val dyNorm = drag.y / ch
                    val newRect = when (corner) {
                        Corner.TOP_LEFT -> Rect(
                            left = (cropRect.left + dxNorm).coerceIn(0f, cropRect.right - MIN_CROP_RATIO),
                            top = (cropRect.top + dyNorm).coerceIn(0f, cropRect.bottom - MIN_CROP_RATIO),
                            right = cropRect.right,
                            bottom = cropRect.bottom,
                        )
                        Corner.TOP_RIGHT -> Rect(
                            left = cropRect.left,
                            top = (cropRect.top + dyNorm).coerceIn(0f, cropRect.bottom - MIN_CROP_RATIO),
                            right = (cropRect.right + dxNorm).coerceIn(cropRect.left + MIN_CROP_RATIO, 1f),
                            bottom = cropRect.bottom,
                        )
                        Corner.BOTTOM_LEFT -> Rect(
                            left = (cropRect.left + dxNorm).coerceIn(0f, cropRect.right - MIN_CROP_RATIO),
                            top = cropRect.top,
                            right = cropRect.right,
                            bottom = (cropRect.bottom + dyNorm).coerceIn(cropRect.top + MIN_CROP_RATIO, 1f),
                        )
                        Corner.BOTTOM_RIGHT -> Rect(
                            left = cropRect.left,
                            top = cropRect.top,
                            right = (cropRect.right + dxNorm).coerceIn(cropRect.left + MIN_CROP_RATIO, 1f),
                            bottom = (cropRect.bottom + dyNorm).coerceIn(cropRect.top + MIN_CROP_RATIO, 1f),
                        )
                    }
                    onCropRectChange(newRect)
                }
            },
    )
}

/**
 * 裁剪比例预设条：自由 / 1:1 / 4:3 / 16:9。
 * 选中自由时 [ratio]=null；其余为宽/高比值。
 */
@Composable
internal fun AspectRatioRow(
    aspectRatio: Float?,
    onSelect: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val options = listOf<Pair<String, Float?>>(
        "自由" to null,
        "1:1" to 1f,
        "4:3" to 4f / 3f,
        "16:9" to 16f / 9f,
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "比例",
            style = typography.caption1,
            color = colors.secondaryLabel,
        )
        options.forEach { (label, ratio) ->
            val isSelected = ratio == aspectRatio
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) colors.systemBlue else colors.tertiaryBackground,
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) colors.systemBlue else colors.separator,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable { onSelect(ratio) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style = typography.caption1,
                    color = if (isSelected) colors.systemBackground else colors.label,
                )
            }
        }
    }
}

/**
 * 按比例 [ratio]（null=取容器内最大居中 70% 区域）计算居中裁剪框。
 * 考虑图片在容器中以 ContentScale.Fit 显示后的实际占据区域，
 * 使裁剪框落在图片可见范围内。
 */
internal fun computeCenteredCropRect(
    ratio: Float?,
    containerSize: IntSize,
    bitmap: Bitmap,
): Rect {
    val cw = containerSize.width.toFloat()
    val ch = containerSize.height.toFloat()
    if (cw <= 0f || ch <= 0f) return Rect(0.15f, 0.15f, 0.85f, 0.85f)
    // 图片在容器内 Fit 后的实际显示区域（像素）
    val scale = minOf(cw / bitmap.width, ch / bitmap.height)
    val dispW = bitmap.width * scale
    val dispH = bitmap.height * scale
    val imgLeft = (cw - dispW) / 2f
    val imgTop = (ch - dispH) / 2f
    // 转为归一化（相对容器）
    val imgLeftN = imgLeft / cw
    val imgTopN = imgTop / ch
    val imgWN = dispW / cw
    val imgHN = dispH / ch

    val targetW: Float
    val targetH: Float
    if (ratio == null) {
        // 自由：取图片显示区域的 70%
        targetW = imgWN * 0.7f
        targetH = imgHN * 0.7f
    } else {
        // 按宽高比在图片显示区域内取最大居中矩形
        if (imgWN / imgHN > ratio) {
            // 图片更宽，以高度为基准
            targetH = imgHN * 0.9f
            targetW = targetH * ratio
        } else {
            targetW = imgWN * 0.9f
            targetH = targetW / ratio
        }
    }
    val leftN = (imgLeftN + (imgWN - targetW) / 2f).coerceIn(0f, 1f)
    val topN = (imgTopN + (imgHN - targetH) / 2f).coerceIn(0f, 1f)
    val rightN = (leftN + targetW).coerceIn(0f, 1f)
    val bottomN = (topN + targetH).coerceIn(0f, 1f)
    return Rect(leftN, topN, rightN, bottomN)
}

/** 裁剪框最小归一化边长，避免拖到不可见。 */
private const val MIN_CROP_RATIO = 0.1f
