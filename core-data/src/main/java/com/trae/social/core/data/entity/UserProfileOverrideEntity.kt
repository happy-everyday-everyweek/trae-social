package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户显式覆盖（用户掌控层）。
 *
 * 由用户反馈智能体应用，覆盖计算值；不衰减，直到用户撤销或 reset。
 * 同 key 新覆盖产生时，旧覆盖 [superseded] 置 true（软删除，保留审计）。
 *
 * - [type] 取值：THEME_BOOST / THEME_SUPPRESS / SCENARIO_DISABLE / SET_ACTIVE_HOURS /
 *   ADD_PREFERENCE / REMOVE_PREFERENCE / CORRECT_NARRATIVE。
 * - [key] 为覆盖键（如主题名 / scenarioId / "active_hours" / preference 文本）。
 * - [value] 为覆盖值 JSON（如权重数值、hours 列表）。
 * - [reason] 触发该覆盖的用户消息原文，便于审计与回溯。
 * - [source] 取值：FEEDBACK_AGENT（智能体应用）/ MANUAL（手动，预留）。
 */
@Entity(
    tableName = "user_profile_overrides",
    indices = [
        Index(value = ["type", "key"]),
        Index(value = ["superseded"])
    ]
)
data class UserProfileOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val key: String,
    val value: String,
    val reason: String,
    val createdAt: Long,
    val source: String,
    val superseded: Boolean = false
)
