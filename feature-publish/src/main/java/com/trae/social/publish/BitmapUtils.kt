package com.trae.social.publish

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import java.io.File
import java.io.FileOutputStream
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * 应用裁剪 + 滤镜，返回新 Bitmap。
 *
 * [cropRect] 为归一化裁剪框（相对容器尺寸），[containerSize] 为容器像素尺寸。
 * 图片以 ContentScale.Fit 显示，故需先将裁剪框从容器坐标换算到图片显示区域坐标，
 * 再按显示缩放比映射回源图像素坐标。
 *
 * 容器尺寸为 0（尚未布局）时回退到源图居中 70% 裁剪，保证可用性。
 */
internal fun applyCropAndFilter(
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
internal fun decodeBitmap(context: android.content.Context, uri: Uri): Bitmap {
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
internal fun saveBitmap(context: android.content.Context, bitmap: Bitmap): String? {
    val dir = File(context.cacheDir, "edit").apply { mkdirs() }
    val file = File(dir, "${System.currentTimeMillis()}.jpg")
    return runCatching {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        file.absolutePath
    }.onFailure { Timber.w(it, "保存编辑结果失败") }.getOrNull()
}

/**
 * 解码后最长边上限（px）。2048 兼顾清晰度与内存（2048×2048×4B≈16MB）。
 */
private const val MAX_DECODE_EDGE = 2048
