package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 评论实体。独立于 [InteractionEntity] 的评论列表存储。
 *
 * 背景：interactions 表的 (tweetId, accountId, type) 唯一索引导致同一用户对同一推文
 * 只能存一条 COMMENT，无法承载多条评论。故新建独立的 comments 表持久化全部评论，
 * 供评论弹层打开时加载展示（含历史 AI 生成的评论）。
 *
 * - tweetId 外键关联 tweets.id，删除推文时级联删除其评论。
 * - authorId 外键关联 accounts.id，删除账号时级联删除其评论。
 * - 索引 tweetId 支撑按推文查询评论列表。
 *
 * @param id 评论 ID（UUID）
 * @param tweetId 所属推文 ID
 * @param authorId 评论作者账号 ID（用户评论为 "user-self"，AI 评论为虚拟账号 ID）
 * @param content 评论正文
 * @param createdAt 创建时间戳（毫秒）
 */
@Entity(
    tableName = "comments",
    indices = [
        Index(value = ["tweetId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = TweetEntity::class,
            parentColumns = ["id"],
            childColumns = ["tweetId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["authorId"],
            onDelete = ForeignKey.CASCADE,
        )
    ]
)
data class CommentEntity(
    @PrimaryKey
    val id: String,
    val tweetId: String,
    val authorId: String,
    val content: String,
    val createdAt: Long
)
