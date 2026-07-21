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

    // #84：gallery 资源为 8 类别 × 25 张 = 200 张唯一图片（见
    // app/src/main/assets/gallery/index.json）。账号总数 221 > 200，鸽巢原理
    // 保证至少 21 个账号必然与其他账号共享头像——此为资源数量上限决定，无法在
    // 纯函数内消除。要彻底消除碰撞需扩充 gallery 资源至 250+ 张或启用
    // avatars/index.txt 显式映射（见 #83），此处仅在资源上限内尽量降低碰撞概率。
    private val avatarCategories = listOf(
        "landscape", "city", "food", "nature",
        "pet", "sport", "tech", "art"
    )
    private const val IMAGES_PER_CATEGORY = 25
    private const val TOTAL_AVATAR_IMAGES = 200 // avatarCategories.size * IMAGES_PER_CATEGORY

    /**
     * 由 avatarSeed 派生确定性头像 asset URI（与 FeedUtils.avatarUriFromSeed 一致）。
     *
     * #84：相比此前「类别 % 8 + 桶内 % 25」的双模组合，改为在 200 张图的全池上取
     * 单一模（flatIndex % 200）再映射到 (类别, 桶内下标)，并对 seedHash 做雪崩混合
     * （[mixHash]）打散 String.hashCode 的位聚集，使相邻 seed 也能映射到远端桶，
     * 在资源上限内尽量降低碰撞概率。鸽巢下限（21 次碰撞）无法消除，见上方注释。
     */
    fun avatarUriFromSeed(avatarSeed: String): String {
        val flatIndex = (mixHash(avatarSeed.hashCode()) and 0x7FFFFFFF) % TOTAL_AVATAR_IMAGES
        val category = avatarCategories[flatIndex / IMAGES_PER_CATEGORY]
        val index = (flatIndex % IMAGES_PER_CATEGORY) + 1
        return "file:///android_asset/gallery/$category/$index.svg"
    }

    /**
     * MurmurHash3 32-bit finalizer（雪崩混合），用于打散 String.hashCode 的位聚集，
     * 使输入的微小变化能均匀传播到所有输出位，降低取模后的聚集碰撞。
     */
    private fun mixHash(h: Int): Int {
        var x = h
        x = x xor (x ushr 16)
        x = x * (0x85EBCA6B.toInt())
        x = x xor (x ushr 13)
        x = x * (0xC2B2AE35.toInt())
        x = x xor (x ushr 16)
        return x
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
        val diffSeconds = TimeUnit.MILLISECONDS.toSeconds(nowMillis - timestampMillis)
        if (diffSeconds < 60L) return "刚刚"
        if (diffSeconds < 3600L) return "${TimeUnit.SECONDS.toMinutes(diffSeconds)} 分钟前"
        if (diffSeconds < 86400L) return "${TimeUnit.SECONDS.toHours(diffSeconds)} 小时前"
        if (diffSeconds < 86400L * 7) return "${TimeUnit.SECONDS.toDays(diffSeconds)} 天前"
        return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestampMillis))
    }

    /**
     * 格式化计数（>=10000 显示为"x.x万"）。
     *
     * 显式指定 [Locale.ROOT]：避免在某些语言环境（如德语/法语区）下默认 Locale
     * 使用逗号作为小数分隔符，导致输出 "x,x万" 与中文语境不一致（#163）。
     */
    fun formatCount(count: Int): String =
        if (count >= 10000) "${String.format(Locale.ROOT, "%.1f", count / 10000.0)}万"
        else count.toString()
}
