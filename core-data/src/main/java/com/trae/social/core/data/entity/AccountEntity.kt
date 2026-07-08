package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 虚拟/真实账号实体。
 *
 * 固定字段（人设种子）：worldview、values、languageStyle、catchphrase、emojiPreference、
 * typoRate、activeWindows、profession、ageRange、culturalBackground。
 * 动态字段（AI 维护摘要）：dynamicLifeStory、dynamicWorkInfo、recentMood。
 * 详细动态字段见 [PersonaDynamicFieldEntity]。
 *
 * @param id UUID v4
 * @param activeWindows 24 槽 bool 数组，标记每小时是否活跃（TypeConverter 序列化为 JSON）
 * @param emojiPreference 常用 emoji 列表（TypeConverter 序列化为 JSON）
 * @param catchphrase 口癖列表（TypeConverter 序列化为 JSON）
 * @param timezone 账号所属时区（IMPL-16：避免跨时区旅行时活跃窗与配额边界漂移）
 */
@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["username"], unique = true),
        Index(value = ["avatarSeed"]),
        Index(value = ["isVirtual"])
    ]
)
data class AccountEntity(
    @PrimaryKey
    val id: String,
    val displayName: String,
    val username: String,
    val avatarSeed: String,
    val bio: String,
    val profession: String,
    val ageRange: String,
    val culturalBackground: String,
    val worldview: String,
    val values: String,
    val languageStyle: String,
    val catchphrase: List<String>,
    val emojiPreference: List<String>,
    val typoRate: Double,
    val activeWindows: List<Boolean>,
    val isVirtual: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val dynamicLifeStory: String,
    val dynamicWorkInfo: String,
    val recentMood: String,
    val timezone: String = DEFAULT_TIMEZONE,
) {
    companion object {
        /** 默认时区：亚洲/上海（人设种子未指定时使用） */
        const val DEFAULT_TIMEZONE = "Asia/Shanghai"
    }
}
