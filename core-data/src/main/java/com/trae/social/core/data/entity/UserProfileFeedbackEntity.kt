package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户与反馈智能体的对话历史（用户掌控层）。
 *
 * 持久化跨会话可回看。
 *
 * - [role] 取值：USER / ASSISTANT。
 * - [appliedActions] 非空时（ASSISTANT 消息）携带已应用 Action 的 JSON 列表。
 * - [rollbackPreviews] 非空时（ASSISTANT 消息）携带回滚预览 JSON，供 UI 渲染确认卡片。
 */
@Entity(
    tableName = "user_profile_feedback",
    indices = [Index(value = ["createdAt"])]
)
data class UserProfileFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val appliedActions: String?,
    val rollbackPreviews: String?,
    val createdAt: Long
)
