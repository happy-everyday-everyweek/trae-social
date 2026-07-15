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
     *
     * IMPL-39：mediaPath 可能是逗号分隔的多图列表，取第一张显示。
     * 保留本函数以兼容仅需首图的调用方（如 CommentSheet）。
     */
    fun toImageUri(mediaPath: String?): String? {
        if (mediaPath.isNullOrBlank()) return null
        // 多图取第一张
        val firstPath = mediaPath.substringBefore(",").trim()
        return toSingleImageUri(firstPath)
    }

    /**
     * 将 mediaPath 解析为 Coil 可加载的 URI 列表（#4 多图信息流）。
     *
     * mediaPath 为逗号分隔的多图列表时，逐项按 [toSingleImageUri] 规则转换后返回全部 URI；
     * null / 空 / 仅含空白项时返回空列表。
     */
    fun toImageUriList(mediaPath: String?): List<String> {
        if (mediaPath.isNullOrBlank()) return emptyList()
        return mediaPath.split(",")
            .map { it.trim() }
            .mapNotNull { toSingleImageUri(it) }
    }

    /**
     * 单条媒体路径 → Coil URI 的统一转换规则，供 [toImageUri] / [toImageUriList] 复用。
     *
     * - 已带 scheme（http/https/file/content）→ 原样返回
     * - 绝对文件路径（以 "/" 开头）→ "file://<path>"
     * - asset 相对路径 → "file:///android_asset/<path>"
     * - 空白 → null
     */
    private fun toSingleImageUri(path: String): String? {
        if (path.isBlank()) return null
        if (path.startsWith("http://") ||
            path.startsWith("https://") ||
            path.startsWith("file://") ||
            path.startsWith("content://")
        ) {
            return path
        }
        if (path.startsWith("/")) return "file://$path"
        return "file:///android_asset/$path"
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
        // #84：鸽巢原理保证 221 账号映射到 200 张图必有碰撞，
        // 使用 seedHash 高低位组合分布更均匀，减少碰撞概率
        val index = ((seedHash and 0x7FFFFFFF) % 25) + 1
        return "file:///android_asset/gallery/$category/$index.svg"
    }
}
