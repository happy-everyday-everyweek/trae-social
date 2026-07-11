package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 推文实体。信息流、时间线、个人主页均基于此表查询。
 *
 * 索引：createdAt（分页排序）、authorId（按作者查询）、deduplicationKey（唯一去重）。
 * IMPL-22：authorId 外键关联 accounts.id，删除账号时级联删除其推文。
 *
 * @param deduplicationKey 调度去重键（accountId + windowStart + sequenceNo），唯一约束
 * @param isAiGenerated 是否为 AI 生成（RISK-12：UI 标识）
 */
@Entity(
    tableName = "tweets",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["authorId"]),
        Index(value = ["deduplicationKey"], unique = true),
        // #108：复合索引，优化 countByAuthorSince/countByAuthorInWindow/getByAuthor 查询
        Index(value = ["authorId", "createdAt"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["authorId"],
            onDelete = ForeignKey.CASCADE,
        )
    ]
)
data class TweetEntity(
    @PrimaryKey
    val id: String,
    val authorId: String,
    val text: String,
    val mediaPath: String?,
    val mediaTheme: String?,
    val createdAt: Long,
    val likeCount: Int,
    val commentCount: Int,
    val retweetCount: Int,
    val isAiGenerated: Boolean,
    val deduplicationKey: String
)
