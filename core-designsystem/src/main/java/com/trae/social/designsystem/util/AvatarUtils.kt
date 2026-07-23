package com.trae.social.designsystem.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 账号头像派生工具（#313）。
 *
 * 此前 `avatarUriFromSeed` + `mixHash` 在 feature-feed / feature-profile /
 * feature-timeline 三个模块逐字复制实现（含 8 类别 × 25 张 = 200 张头像池常量），
 * 任一处修改需同步三处。抽到 core-designsystem 后统一引用。
 *
 * #84：在 200 张图的全池上取单一模（flatIndex % 200）再映射到 (类别, 桶内下标)，
 * 对 seedHash 做雪崩混合（[mixHash]）打散 String.hashCode 的位聚集，
 * 使相邻 seed 也能映射到远端桶，降低碰撞概率。
 */
object AvatarUtils {

    private val avatarCategories = listOf(
        "landscape", "city", "food", "nature",
        "pet", "sport", "tech", "art",
    )
    private const val IMAGES_PER_CATEGORY = 25
    private const val TOTAL_AVATAR_IMAGES = 200 // avatarCategories.size * IMAGES_PER_CATEGORY

    /**
     * 由 avatarSeed 派生确定性头像 asset URI。
     *
     * 账号总数 221 > 200，鸽巢原理保证至少 21 个账号必然与其他账号共享头像——
     * 此为资源数量上限决定，无法在纯函数内消除。
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
}

/**
 * 时间格式化工具（#313）。
 *
 * 此前 `formatRelativeTime` 在 feature-feed / feature-profile 两个模块逐字复制实现，
 * 抽到 core-designsystem 后统一引用。
 */
object TimeFormatUtils {

    private const val MINUTE_SECONDS = 60L
    private const val HOUR_SECONDS = 3600L
    private const val DAY_SECONDS = 86400L

    /**
     * 将时间戳格式化为相对时间文案。
     *
     * - < 1 分钟：刚刚
     * - < 1 小时：N 分钟前
     * - < 1 天：N 小时前
     * - < 7 天：N 天前
     * - 更早：MM-dd
     */
    fun formatRelativeTime(
        timestampMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
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
}
