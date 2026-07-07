package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 互动类型枚举。
 */
enum class InteractionType {
    LIKE,
    COMMENT,
    RETWEET,
    FOLLOW
}

/**
 * 互动记录实体。记录 AI 账号与用户/彼此间的点赞、评论、转发、关注。
 *
 * 调度流程：写入时 scheduledAt 为预计触发时刻，executedAt 为空；
 * 执行后 markExecuted 写入 executedAt。
 *
 * 索引：scheduledAt（查询待执行互动）。
 */
@Entity(
    tableName = "interactions",
    indices = [
        Index(value = ["scheduledAt"]),
        Index(value = ["tweetId"]),
        Index(value = ["accountId"]),
        Index(value = ["tweetId", "accountId", "type"], unique = true)
    ]
)
data class InteractionEntity(
    @PrimaryKey
    val id: String,
    val tweetId: String,
    val accountId: String,
    val type: InteractionType,
    val content: String?,
    val createdAt: Long,
    val scheduledAt: Long,
    val executedAt: Long?
)
