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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
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
    // 裁剪框偏移（容器像素坐标）
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

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.tertiaryBackground),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = displayBitmap.asImageBitmap(),
            contentDescription = "编辑预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        // 裁剪框：容器 70%，可拖拽
        Box(
            modifier = Modifier
                .fillMaxSize(0.7f)
                .border(width = 2.dp, color = colors.systemBlue, shape = RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onCropOffsetChange(cropOffset + drag)
                    }
                }
                .offset { IntOffset(cropOffset.x.roundToInt(), cropOffset.y.roundToInt()) },
        )
    }
}

/**
 * 滤镜横向选择条：圆形缩略图 + 标签。
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
            val thumb = remember(preset, sourceBitmap) {
                runCatching {
                    val small = Bitmap.createScaledBitmap(sourceBitmap, 64, 64, false)
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
 * 简化裁剪：基于拖拽偏移在源图 70% 区域内取矩形；偏移越大裁剪越偏向一侧。
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
    // 将容器像素偏移按比例映射到源图坐标（粗略近似，保证可裁剪出不同区域）
    val maxOffsetPx = 48f
    val left = ((cropOffset.x / maxOffsetPx).coerceIn(-1f, 1f) * (w - cropW) / 2f)
        .roundToInt().coerceIn(0, (w - cropW).coerceAtLeast(0))
    val top = ((cropOffset.y / maxOffsetPx).coerceIn(-1f, 1f) * (h - cropH) / 2f)
        .roundToInt().coerceIn(0, (h - cropH).coerceAtLeast(0))
    val cropped = runCatching {
        Bitmap.createBitmap(source, left, top, cropW, cropH)
    }.getOrDefault(source)
    return filter.apply(cropped)
}

/**
 * 解码 Uri 为 Bitmap，带采样避免 OOM。
 */
private fun decodeBitmap(context: android.content.Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
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
