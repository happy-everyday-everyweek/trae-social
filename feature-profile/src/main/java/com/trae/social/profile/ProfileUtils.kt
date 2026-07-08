package com.trae.social.profile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 个人主页工具函数（与 feature-feed 的 FeedUtils 等价，避免跨 feature 模块依赖）。
 *
 * 提供 avatarSeed 派生头像 URI、mediaPath 转 Coil URI、相对时间格式化。
 */
internal object ProfileUtils {

    /**
     * 由 avatarSeed 派生确定性头像 asset URI（与 FeedUtils.avatarUriFromSeed 一致）。
     */
    fun avatarUriFromSeed(avatarSeed: String): String {
        val categories = listOf(
            "landscape", "city", "food", "nature",
            "pet", "sport", "tech", "art"
        )
        val seedHash = avatarSeed.hashCode()
        val category = categories[((seedHash % categories.size) + categories.size) % categories.size]
        val index = ((seedHash % 25) + 25) % 25 + 1
        return "file:///android_asset/gallery/$category/$index.svg"
    }

    /**
     * 将 mediaPath 转换为 Coil 可加载的 URI。
     */
    fun toImageUri(mediaPath: String?): String? {
        if (mediaPath.isNullOrBlank()) return null
        if (mediaPath.startsWith("http://") ||
            mediaPath.startsWith("https://") ||
            mediaPath.startsWith("file://") ||
            mediaPath.startsWith("content://")
        ) {
            return mediaPath
        }
        if (mediaPath.startsWith("/")) return "file://$mediaPath"
        return "file:///android_asset/$mediaPath"
    }

    /**
     * 将时间戳格式化为相对时间文案。
     */
    fun formatRelativeTime(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diffSeconds = TimeUnit.MILLISECONDS.toSeconds(nowMillis - timestampMillis)
        if (diffSeconds < 60L) return "刚刚"
        if (diffSeconds < 3600L) return "${TimeUnit.SECONDS.toMinutes(diffSeconds)} 分钟前"
        if (diffSeconds < 86400L) return "${TimeUnit.SECONDS.toHours(diffSeconds)} 小时前"
        if (diffSeconds < 86400L * 7) return "${TimeUnit.SECONDS.toDays(diffSeconds)} 天前"
        return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestampMillis))
    }

    /**
     * 格式化计数（>=10000 显示为"x.x万"）。
     */
    fun formatCount(count: Int): String =
        if (count >= 10000) "${String.format(Locale.getDefault(), "%.1f", count / 10000.0)}万"
        else count.toString()
}
