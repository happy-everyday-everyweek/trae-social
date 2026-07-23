package com.trae.social.profile

import com.trae.social.designsystem.util.AvatarUtils
import com.trae.social.designsystem.util.TimeFormatUtils
import java.util.Locale

/**
 * 个人主页工具函数（与 feature-feed 的 FeedUtils 等价，避免跨 feature 模块依赖）。
 *
 * 提供 avatarSeed 派生头像 URI、mediaPath 转 Coil URI、相对时间格式化。
 */
internal object ProfileUtils {

    /**
     * 由 avatarSeed 派生确定性头像 asset URI。
     *
     * 委托到 [AvatarUtils.avatarUriFromSeed]（#313 抽取），与 Feed / Timeline
     * 共享同一实现，避免三处复制产生分歧。
     */
    fun avatarUriFromSeed(avatarSeed: String): String {
        return AvatarUtils.avatarUriFromSeed(avatarSeed)
    }

    /**
     * 将 mediaPath 转换为 Coil 可加载的 URI。
     * IMPL-39：mediaPath 可能是逗号分隔的多图列表，取第一张显示。
     */
    fun toImageUri(mediaPath: String?): String? {
        if (mediaPath.isNullOrBlank()) return null
        // 多图取第一张
        val firstPath = mediaPath.substringBefore(",").trim()
        return toSingleImageUri(firstPath)
    }

    /**
     * 将 mediaPath 解析为 Coil 可加载的 URI 列表（#191：多图全屏翻页）。
     *
     * mediaPath 为逗号分隔的多图列表时，逐项按 [toSingleImageUri] 规则转换后返回
     * 全部 URI；null / 空 / 仅含空白项时返回空列表。与 FeedUtils.toImageUriList 等价。
     */
    fun toImageUriList(mediaPath: String?): List<String> {
        if (mediaPath.isNullOrBlank()) return emptyList()
        return mediaPath.split(",")
            .map { it.trim() }
            .mapNotNull { toSingleImageUri(it) }
    }

    /**
     * 单条媒体路径 → Coil URI 的统一转换规则，供 [toImageUri] / [toImageUriList] 复用。
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
     * 将时间戳格式化为相对时间文案。
     */
    fun formatRelativeTime(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        return TimeFormatUtils.formatRelativeTime(timestampMillis, nowMillis)
    }

    /**
     * 格式化计数（>=10000 显示为"x.x万"）。
     *
     * 显式指定 [Locale.ROOT]：避免在某些语言环境（如德语/法语区）下默认 Locale
     * 使用逗号作为小数分隔符，导致输出 "x,x万" 与中文语境不一致（#163）。
     */
    fun formatCount(count: Int): String =
        if (count >= WAN_THRESHOLD) "${String.format(Locale.ROOT, "%.1f", count / WAN_THRESHOLD.toDouble())}万"
        else count.toString()
}

/**
 * "万" 显示阈值：计数 >= 此值时格式化为 "x.x万"（#285：与 feature-feed TweetInteractionButton 对齐）。
 */
private const val WAN_THRESHOLD = 10000
