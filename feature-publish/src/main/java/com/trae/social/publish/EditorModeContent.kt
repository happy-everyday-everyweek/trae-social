package com.trae.social.publish

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 滤镜预设（SubTask 14.6）。基于 [ColorMatrix] 实现。
 */
enum class FilterPreset(val label: String) {
    ORIGINAL("原图"),
    WARM("暖色"),
    COOL("冷色"),
    GRAYSCALE("黑白"),
    SEPIA("复古");

    /**
     * 将滤镜应用到源 Bitmap，返回新 Bitmap。原图直接返回源引用。
     */
    fun apply(source: Bitmap): Bitmap {
        val matrix = when (this) {
            ORIGINAL -> return source
            WARM -> floatArrayOf(
                1.2f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 0.8f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
            COOL -> floatArrayOf(
                0.8f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1.2f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
            GRAYSCALE -> floatArrayOf(
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
            SEPIA -> floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        }
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix))
            isAntiAlias = true
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }
}

/**
 * 编辑器模式内容（SubTask 14.6）。
 *
 * 相册选图 + 裁剪（自实现简化版可拖拽矩形） + 滤镜（5 个 ColorMatrix 预设）。
 *
 * IMPL-36：原实现裁剪区域写死 70%、拖拽 48px 才到边沿且坐标系为硬编码近似；
 * `decodeBitmap` 固定 `inSampleSize=2` 对 4000×6000 大图仍解码到 2000×3000≈24MB，
 * 滤镜切换累积分配合计上百 MB，低端机 OOM；Bitmap 不 recycle。
 * 现改为：(1) 用 `onSizeChanged` 取容器真实尺寸，按比例精确映射裁剪框到源图坐标；
 * (2) `decodeBitmap` 先 `inJustDecodeBounds=true` 探测尺寸，动态算 inSampleSize
 * 使最长边降到 [MAX_DECODE_EDGE] 以下；(3) 缩略图降到 32×32；(4) 中间 Bitmap 用完 recycle。
 *
 * #9：新增配文输入，用户可在编辑器内直接输入配文，与发布流程共享同一 caption 状态。
 *
 * @param onEditComplete 编辑完成回调（传入落盘文件绝对路径）
 * @param caption 当前配文文本
 * @param onCaptionChange 配文变更回调
 */
@Composable
fun EditorModeContent(
    onEditComplete: (String) -> Unit,
    caption: String,
    onCaptionChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedFilter by remember { mutableStateOf(FilterPreset.ORIGINAL) }
    // 裁剪框以容器尺寸的比例表示（left/top/right/bottom ∈ [0,1]），默认居中 70%
    var cropRect by remember { mutableStateOf(Rect(0.15f, 0.15f, 0.85f, 0.85f)) }
    // 当前裁剪比例（null = 自由）；切换图片时重置
    var aspectRatio by remember { mutableStateOf<Float?>(null) }
    // 容器真实像素尺寸，供裁剪坐标精确映射到源图
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // 选图/换图时重置裁剪框与比例
    fun resetCrop() {
        cropRect = Rect(0.15f, 0.15f, 0.85f, 0.85f)
        aspectRatio = null
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        pickedUri = uri
        if (uri != null) {
            sourceBitmap = runCatching { decodeBitmap(context, uri) }.getOrNull()
            resetCrop()
        }
    }

    // 首次进入自动打开相册
    LaunchedEffect(Unit) {
        if (pickedUri == null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.systemBackground)) {
        val bitmap = sourceBitmap
        if (pickedUri == null || bitmap == null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "从相册选择一张图片",
                    style = typography.headline,
                    color = colors.label,
                )
                ActionButton(
                    text = "选择图片",
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // 预览 + 可拖拽裁剪框（边角可调大小）
            CropOverlay(
                bitmap = bitmap,
                filter = selectedFilter,
                cropRect = cropRect,
                onCropRectChange = { cropRect = it },
                containerSize = containerSize,
                onContainerSizeChange = { containerSize = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
            )

            // 裁剪比例预设条（自由 / 1:1 / 4:3 / 16:9）
            AspectRatioRow(
                aspectRatio = aspectRatio,
                onSelect = { ratio ->
                    aspectRatio = ratio
                    // 切换比例时按容器与源图比例重新居中计算裁剪框
                    if (containerSize != IntSize.Zero && bitmap != null) {
                        cropRect = computeCenteredCropRect(ratio, containerSize, bitmap)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // 滤镜选择条
            FilterRow(
                selected = selectedFilter,
                onSelect = { selectedFilter = it },
                sourceBitmap = bitmap,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )

            // #9：编辑器内配文输入，与发布流程共享同一 caption 状态
            CaptionInput(
                text = caption,
                onTextChanged = onCaptionChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // 底部操作
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    text = "重新选择",
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = "确认裁剪",
                    onClick = {
                        val edited = applyCropAndFilter(bitmap, cropRect, containerSize, selectedFilter)
                        val path = saveBitmap(context, edited)
                        // IMPL-36：裁剪结果落盘后立即释放，避免叠加原图占用双倍内存
                        if (edited !== bitmap) edited.recycle()
                        if (path != null) onEditComplete(path)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

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
private fun CropOverlay(
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
    val displayBitmap = remember(filter, bitmap) { filter.apply(bitmap) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.tertiaryBackground)
            .onSizeChanged { onContainerSizeChange(it) },
    ) {
        Image(
            bitmap = displayBitmap.asImageBitmap(),
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
private fun AspectRatioRow(
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
private fun computeCenteredCropRect(
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

/**
 * 滤镜横向选择条：圆形缩略图 + 标签。
 *
 * IMPL-36：缩略图从 64×64 降到 32×32，降低 5 个 preset 累计内存占用。
 */
@Composable
private fun FilterRow(
    selected: FilterPreset,
    onSelect: (FilterPreset) -> Unit,
    sourceBitmap: Bitmap,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(FilterPreset.values().toList()) { preset ->
            val isSelected = preset == selected
            // IMPL-36：32×32 缩略图，大幅降低内存
            val thumb = remember(preset, sourceBitmap) {
                runCatching {
                    val small = Bitmap.createScaledBitmap(sourceBitmap, 32, 32, false)
                    preset.apply(small)
                }.getOrDefault(sourceBitmap)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 0.dp,
                            color = if (isSelected) colors.systemBlue else androidx.compose.ui.graphics.Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable { onSelect(preset) },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = preset.label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(CircleShape),
                    )
                }
                Text(
                    text = preset.label,
                    style = typography.caption1,
                    color = if (isSelected) colors.systemBlue else colors.secondaryLabel,
                )
            }
        }
    }
}

/**
 * 应用裁剪 + 滤镜，返回新 Bitmap。
 *
 * [cropRect] 为归一化裁剪框（相对容器尺寸），[containerSize] 为容器像素尺寸。
 * 图片以 ContentScale.Fit 显示，故需先将裁剪框从容器坐标换算到图片显示区域坐标，
 * 再按显示缩放比映射回源图像素坐标。
 *
 * 容器尺寸为 0（尚未布局）时回退到源图居中 70% 裁剪，保证可用性。
 */
private fun applyCropAndFilter(
    source: Bitmap,
    cropRect: Rect,
    containerSize: IntSize,
    filter: FilterPreset,
): Bitmap {
    val w = source.width
    val h = source.height

    val left: Int
    val top: Int
    val cropW: Int
    val cropH: Int
    if (containerSize == IntSize.Zero) {
        // 回退：源图居中 70%
        cropW = (w * 0.7f).toInt().coerceIn(1, w)
        cropH = (h * 0.7f).toInt().coerceIn(1, h)
        left = ((w - cropW) / 2f).roundToInt().coerceIn(0, (w - cropW).coerceAtLeast(0))
        top = ((h - cropH) / 2f).roundToInt().coerceIn(0, (h - cropH).coerceAtLeast(0))
    } else {
        val cw = containerSize.width.toFloat()
        val ch = containerSize.height.toFloat()
        // 图片 Fit 后的显示区域（像素）与缩放比
        val scale = minOf(cw / w, ch / h)
        val dispW = w * scale
        val dispH = h * scale
        val imgLeft = (cw - dispW) / 2f
        val imgTop = (ch - dispH) / 2f
        // 容器归一化裁剪框 -> 容器像素 -> 图片显示像素 -> 源图像素
        val cropLeftPx = cropRect.left * cw
        val cropTopPx = cropRect.top * ch
        val cropRightPx = cropRect.right * cw
        val cropBottomPx = cropRect.bottom * ch
        val sx = ((cropLeftPx - imgLeft) / scale).roundToInt().coerceIn(0, w - 1)
        val sy = ((cropTopPx - imgTop) / scale).roundToInt().coerceIn(0, h - 1)
        val sx2 = ((cropRightPx - imgLeft) / scale).roundToInt().coerceIn(sx + 1, w)
        val sy2 = ((cropBottomPx - imgTop) / scale).roundToInt().coerceIn(sy + 1, h)
        left = sx
        top = sy
        cropW = sx2 - sx
        cropH = sy2 - sy
    }

    val cropped = runCatching {
        Bitmap.createBitmap(source, left, top, cropW, cropH)
    }.getOrDefault(source)
    // IMPL-36：若产生了新 Bitmap（裁剪有效），源图此处不再需要，但由调用方管理生命周期，
    // 这里只确保裁剪产物在滤镜应用后如产生新 Bitmap 会被回收（FilterPreset.apply 对非 ORIGINAL 返回新 Bitmap）。
    val result = filter.apply(cropped)
    if (result !== cropped && cropped !== source) {
        cropped.recycle()
    }
    return result
}

/**
 * 解码 Uri 为 Bitmap，带动态采样避免 OOM（IMPL-36）。
 *
 * 原实现固定 `inSampleSize=2`，4000×6000 大图仍解码到 2000×3000≈24MB。
 * 现先用 `inJustDecodeBounds=true` 探测原图尺寸，按 [MAX_DECODE_EDGE] 动态算
 * inSampleSize（2 的幂），使解码后最长边不超过 [MAX_DECODE_EDGE]。
 */
private fun decodeBitmap(context: android.content.Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver
    // IMPL-36：先探测尺寸
    val boundsOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { input ->
        android.graphics.BitmapFactory.decodeStream(input, null, boundsOpts)
    } ?: throw IllegalStateException("打开输入流失败")
    val srcW = boundsOpts.outWidth.coerceAtLeast(1)
    val srcH = boundsOpts.outHeight.coerceAtLeast(1)
    val longestEdge = maxOf(srcW, srcH)
    var sample = 1
    while (longestEdge / sample > MAX_DECODE_EDGE) {
        sample *= 2
    }
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri)?.use { input ->
        android.graphics.BitmapFactory.decodeStream(input, null, opts)
            ?: throw IllegalStateException("解码图片失败")
    } ?: throw IllegalStateException("打开输入流失败")
}

/**
 * 将 Bitmap 以 JPEG 落盘到 cacheDir/edit/<timestamp>.jpg，返回绝对路径。
 */
private fun saveBitmap(context: android.content.Context, bitmap: Bitmap): String? {
    val dir = File(context.cacheDir, "edit").apply { mkdirs() }
    val file = File(dir, "${System.currentTimeMillis()}.jpg")
    return runCatching {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        file.absolutePath
    }.onFailure { Timber.w(it, "保存编辑结果失败") }.getOrNull()
}

/**
 * 解码后最长边上限（px）。2048 兼顾清晰度与内存（2048×2048×4B≈16MB）。
 */
private const val MAX_DECODE_EDGE = 2048
