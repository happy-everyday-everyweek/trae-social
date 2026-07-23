package com.trae.social.publish

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

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
