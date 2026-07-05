package com.trae.social.core.data.seed

import kotlinx.serialization.Serializable

/**
 * 人设 JSON 数据传输对象（与 Task 7 输出格式约定）。
 *
 * 对应 assets/personas/personas_*.json 中每条记录。
 */
@Serializable
data class PersonaDto(
    val id: String,
    val displayName: String,
    val username: String,
    val avatarSeed: String,
    val bio: String,
    val profession: String = "",
    val ageRange: String = "",
    val culturalBackground: String = "",
    val worldview: String = "",
    val values: String = "",
    val languageStyle: String = "",
    val catchphrase: List<String> = emptyList(),
    val emojiPreference: List<String> = emptyList(),
    val typoRate: Double = 0.0,
    val activeWindows: List<Boolean> = emptyList(),
    val historicalTweets: List<HistoricalTweetDto> = emptyList()
)

/**
 * 人设自带历史推文（RISK-14：营造账号早已存在）。
 *
 * @param daysAgo 发布于几天前（1-30）
 */
@Serializable
data class HistoricalTweetDto(
    val text: String,
    val mediaTheme: String? = null,
    val daysAgo: Int
)
