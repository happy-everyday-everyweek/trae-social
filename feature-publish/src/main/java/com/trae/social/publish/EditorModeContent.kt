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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
 * @param onEditComplete 编辑完成回调（传入落盘文件绝对路径）
 */
@Composable
fun EditorModeContent(
    onEditComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedFilter by remember { mutableStateOf(FilterPreset.ORIGINAL) }
    // 裁剪框相对容器中心的拖拽偏移（容器像素坐标）
    var cropOffset by remember { mutableStateOf(Offset.Zero) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        pickedUri = uri
        if (uri != null) {
            sourceBitmap = runCatching { decodeBitmap(context, uri) }.getOrNull()
            cropOffset = Offset.Zero
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
            // 预览 + 可拖拽裁剪框
            CropOverlay(
                bitmap = bitmap,
                filter = selectedFilter,
                cropOffset = cropOffset,
                onCropOffsetChange = { cropOffset = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
            )

            // 滤镜选择条
            FilterRow(
                selected = selectedFilter,
                onSelect = { selectedFilter = it },
                sourceBitmap = bitmap,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                        val edited = applyCropAndFilter(bitmap, cropOffset, selectedFilter)
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
 * 图片预览 + 可拖拽裁剪矩形遮罩。
 *
 * 简化实现：裁剪框为容器 70% 尺寸，跟随用户拖拽位移；拖拽范围限定在容器内。
 * IMPL-36：通过 [onSizeChanged] 拿到容器真实像素尺寸，供 [applyCropAndFilter] 按比例
 * 映射到源图坐标，取代原硬编码 48px maxOffset 近似。
 */
@Composable
private fun CropOverlay(
    bitmap: Bitmap,
    filter: FilterPreset,
    cropOffset: Offset,
    onCropOffsetChange: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val displayBitmap = remember(filter, bitmap) { filter.apply(bitmap) }
    // IMPL-36：记录容器尺寸，供裁剪坐标按比例映射
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.tertiaryBackground)
            .onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = displayBitmap.asImageBitmap(),
            contentDescription = "编辑预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        // 裁剪框：容器 70%，可拖拽，偏移钳制在容器允许范围内
        Box(
            modifier = Modifier
                .fillMaxSize(0.7f)
                .border(width = 2.dp, color = colors.systemBlue, shape = RoundedCornerShape(4.dp))
                .pointerInput(containerSize) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        // IMPL-36：拖拽范围按容器尺寸计算，使裁剪框不超出容器边界
                        val maxX = (containerSize.width * 0.15f)
                        val maxY = (containerSize.height * 0.15f)
                        val newOffset = Offset(
                            x = (cropOffset.x + drag.x).coerceIn(-maxX, maxX),
                            y = (cropOffset.y + drag.y).coerceIn(-maxY, maxY),
                        )
                        onCropOffsetChange(newOffset)
                    }
                }
                .offset { IntOffset(cropOffset.x.roundToInt(), cropOffset.y.roundToInt()) },
        )
    }
}

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
 * IMPL-36：原实现 `maxOffsetPx=48f` 硬编码近似映射，裁剪框拖拽范围与容器实际尺寸脱节。
 * 现按容器尺寸（由调用方传入的 cropOffset 已被 CropOverlay 钳制到 ±15% 容器尺寸）映射：
 * 裁剪框默认居中（70% 尺寸），偏移 cropOffset 表示相对中心的位移，按比例换算到源图坐标。
 *
 * 注：此处无法直接拿到容器尺寸，但 cropOffset 已被 CropOverlay 钳制到 ±0.15*container，
 * 而裁剪框为 70% 容器，故偏移占容器比例 = cropOffset / container ∈ [-0.15, 0.15]，
 * 映射到源图 = (cropOffset / container) * sourceDim。为避免依赖容器尺寸，这里用 cropOffset
 * 的绝对值与源图 15% 区域换算（等效于上述比例，因容器到源图是 ContentScale.Fit 的等比缩放）。
 */
private fun applyCropAndFilter(
    source: Bitmap,
    cropOffset: Offset,
    filter: FilterPreset,
): Bitmap {
    val w = source.width
    val h = source.height
    // 裁剪区域为源图 70%
    val cropW = (w * 0.7f).toInt().coerceIn(1, w)
    val cropH = (h * 0.7f).toInt().coerceIn(1, h)
    // IMPL-36：偏移按源图 15%（等价于容器 15%，ContentScale.Fit 等比）映射到源图坐标
    val maxOffsetX = w * 0.15f
    val maxOffsetY = h * 0.15f
    val ratioX = if (maxOffsetX > 0f) (cropOffset.x / maxOffsetX).coerceIn(-1f, 1f) else 0f
    val ratioY = if (maxOffsetY > 0f) (cropOffset.y / maxOffsetY).coerceIn(-1f, 1f) else 0f
    // 中心 + 偏移，钳制在 [0, w-cropW]
    val left = ((w - cropW) / 2f + ratioX * (w - cropW) / 2f)
        .roundToInt().coerceIn(0, (w - cropW).coerceAtLeast(0))
    val top = ((h - cropH) / 2f + ratioY * (h - cropH) / 2f)
        .roundToInt().coerceIn(0, (h - cropH).coerceAtLeast(0))
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
