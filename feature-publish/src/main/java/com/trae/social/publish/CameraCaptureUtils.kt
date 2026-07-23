package com.trae.social.publish

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executor
import timber.log.Timber

/** JPEG 压缩质量（0-100）。与 BitmapUtils 保持一致，避免两处漂移（#285）。 */
internal const val JPEG_QUALITY = 90

/**
 * 拍照正方形裁剪目标边长（px）：采样后不低于此值，兼顾清晰度与内存。
 */
private const val CAPTURE_TARGET_EDGE_PX = 1080

/**
 * 执行拍照，将 JPEG 落盘到 cacheDir/capture/<timestamp>.jpg。
 */
internal fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    executor: Executor,
    onResult: (String?) -> Unit,
) {
    if (imageCapture == null) {
        onResult(null)
        return
    }
    val captureDir = File(context.cacheDir, "capture").apply { mkdirs() }
    // review 第 5 轮修复：连拍/快速重拍可能在同一毫秒内产生相同时间戳，导致文件名相同——
    // 后写覆盖前写（丢图）且 CapturePreviewBar 用路径作 LazyRow key 会抛 "Key must be unique"。
    // 追加 UUID 保证文件名唯一。
    val file = File(captureDir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.jpg")
    val output = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        output,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                Timber.i("拍照落盘 %s", file.absolutePath)
                onResult(file.absolutePath)
            }

            override fun onError(exception: ImageCaptureException) {
                Timber.w(exception, "拍照失败")
                onResult(null)
            }
        },
    )
}

/**
 * 跳转应用设置页（权限设置）。
 */
internal fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

/**
 * IMPL-35 / IMPL-36：将 JPEG 中心裁剪为正方形，覆盖原文件。
 * 在拍照回调线程执行，避免阻塞 UI。
 *
 * P1-5：使用 inJustDecodeBounds 探测尺寸 + 动态 inSampleSize，
 * 避免大图全量解码导致 OOM。
 */
internal fun cropToSquare(path: String): String? {
    return runCatching {
        // P1-5：先探测原图尺寸
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOpts)
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return null

        // 目标正方形边长 = min(origW, origH)，采样后不低于 [CAPTURE_TARGET_EDGE_PX]
        val targetSize = minOf(origW, origH)
        val sampleTarget = if (targetSize > CAPTURE_TARGET_EDGE_PX) CAPTURE_TARGET_EDGE_PX else targetSize
        var sampleSize = 1
        while (minOf(origW, origH) / (sampleSize * 2) >= sampleTarget) {
            sampleSize *= 2
        }

        // 按采样率解码
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val original = BitmapFactory.decodeFile(path, decodeOpts) ?: return null

        val size = minOf(original.width, original.height)
        val x = (original.width - size) / 2
        val y = (original.height - size) / 2
        val cropped = Bitmap.createBitmap(original, x, y, size, size)
        if (cropped !== original) original.recycle()
        FileOutputStream(path).use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        cropped.recycle()
        path
    }.onFailure { Timber.w(it, "正方形裁剪失败 %s", path) }.getOrNull()
}

/**
 * 闪光灯模式映射到 CameraX 常量。
 */
internal fun FlashMode.toCameraXFlash(): Int = when (this) {
    FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
    FlashMode.ON -> ImageCapture.FLASH_MODE_ON
    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
}
