package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户行为原始事件（捕获层落库）。
 *
 * 记录真实用户在界面上的操作（浏览、停留、点赞、评论、转发、收藏、关注、发布、
 * 导航、引导、用户反馈等），供基础分析层与 LLM 深度画像层消费。
 *
 * - [extra] 为版本化 JSON，包含 schemaVer / drivenByProfile / scenarioId / group /
 *   imageTheme / captionLen / imageCount / fromTab 等聚合上下文，不存评论文本原文。
 * - [occurredAt] 由调用方填写，分析层按其排序而非插入顺序，容忍批写乱序。
 */
@Entity(
    tableName = "user_action_events",
    indices = [
        Index(value = ["occurredAt"]),
        Index(value = ["type", "occurredAt"]),
        Index(value = ["session"]),
        Index(value = ["targetId", "type"])
    ]
)
data class UserActionEventEntity(
    @PrimaryKey val id: String,
    val type: String,
    val screen: String,
    val targetId: String?,
    val targetKind: String?,
    val extra: String?,
    val durationMs: Long?,
    val occurredAt: Long,
    val session: String
)
