package com.trae.social.feed

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 信息流工具函数集合。
 *
 * 提供相对时间格式化、资源路径转换与头像 seed 派生，供 TweetCard / ViewModel 复用。
 */
object FeedUtils {

    private const val MINUTE_SECONDS = 60L
    private const val HOUR_SECONDS = 3600L
    private const val DAY_SECONDS = 86400L

    /**
     * 将时间戳格式化为相对时间文案。
     *
     * - < 1 分钟：刚刚
     * - < 1 小时：N 分钟前
     * - < 24 小时：N 小时前
     * - < 7 天：N 天前
     * - 更早：MM-dd
     */
    fun formatRelativeTime(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diffSeconds = TimeUnit.MILLISECONDS.toSeconds(nowMillis - timestampMillis)
        if (diffSeconds < MINUTE_SECONDS) return "刚刚"
        if (diffSeconds < HOUR_SECONDS) {
            val minutes = TimeUnit.SECONDS.toMinutes(diffSeconds)
            return "$minutes 分钟前"
        }
        if (diffSeconds < DAY_SECONDS) {
            val hours = TimeUnit.SECONDS.toHours(diffSeconds)
            return "$hours 小时前"
        }
        if (diffSeconds < DAY_SECONDS * 7) {
            val days = TimeUnit.SECONDS.toDays(diffSeconds)
            return "$days 天前"
        }
        val format = SimpleDateFormat("MM-dd", Locale.getDefault())
        return format.format(Date(timestampMillis))
    }

    /**
     * 将 mediaPath 转换为 Coil 可加载的 URI。
     *
     * - asset 相对路径（如 "gallery/landscape/3.svg"）→ "file:///android_asset/gallery/landscape/3.svg"
     * - 绝对文件路径（以 "/" 开头）→ "file://<path>"
     * - 已带 scheme（http/https/file/content）→ 原样返回
     * - null / 空 → null
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
     * 由 avatarSeed 派生确定性头像 asset URI。
     *
     * 当前未提供离线头像生成器，按 seed 哈希映射到 gallery 中的某张 SVG，
     * 保证同一账号头像稳定，不同账号尽量不同。后续接入正式头像生成器可替换此处。
     */
    fun avatarUriFromSeed(avatarSeed: String): String {
        val categories = listOf(
            "landscape", "city", "food", "nature",
            "pet", "sport", "tech", "art"
        )
        val seedHash = avatarSeed.hashCode()
        val category = categories[((seedHash % categories.size) + categories.size) % categories.size]
        // 每类 25 张图，取 1..25
        val index = ((seedHash % 25) + 25) % 25 + 1
        return "file:///android_asset/gallery/$category/$index.svg"
    }
}
