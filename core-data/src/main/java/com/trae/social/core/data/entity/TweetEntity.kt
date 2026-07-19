package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 推文实体。信息流、时间线、个人主页均基于此表查询。
 *
 * 索引：createdAt（分页排序）、deduplicationKey（唯一去重）、(authorId, createdAt) 复合索引。
 * IMPL-22：authorId 外键关联 accounts.id，删除账号时级联删除其推文。
 *
 * #227：原 `Index(value = ["authorId"])` 单列索引已被复合索引 `(authorId, createdAt)`
 * 的最左前缀覆盖（SQLite 最左前缀原则），删除以避免 INSERT/UPDATE 写放大。
 *
 * @param deduplicationKey 调度去重键（accountId + windowStart + sequenceNo），唯一约束
 * @param isAiGenerated 是否为 AI 生成（RISK-12：UI 标识）
 */
@Entity(
    tableName = "tweets",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["deduplicationKey"], unique = true),
        // #108：复合索引，优化 countByAuthorSince/countByAuthorInWindow/getByAuthor 查询
        // #227：最左前缀覆盖原 authorId 单列索引，不再单独声明
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
