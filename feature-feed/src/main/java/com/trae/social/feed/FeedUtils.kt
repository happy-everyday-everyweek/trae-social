package com.trae.social.feed

import com.trae.social.designsystem.util.AvatarUtils
import com.trae.social.designsystem.util.TimeFormatUtils

/**
 * 信息流工具函数集合。
 *
 * 提供相对时间格式化、资源路径转换与头像 seed 派生，供 TweetCard / ViewModel 复用。
 */
object FeedUtils {

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
        return TimeFormatUtils.formatRelativeTime(timestampMillis, nowMillis)
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
     * 委托到 [AvatarUtils.avatarUriFromSeed]（#313 抽取），与 Profile / Timeline
     * 共享同一实现，避免三处复制产生分歧。
     */
    fun avatarUriFromSeed(avatarSeed: String): String {
        return AvatarUtils.avatarUriFromSeed(avatarSeed)
    }
}
