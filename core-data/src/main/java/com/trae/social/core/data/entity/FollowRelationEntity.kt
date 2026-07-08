package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 关注关系实体。复合主键 (followerId, followeeId)。
 * IMPL-22：followerId / followeeId 外键关联 accounts.id，删除账号时级联删除其关注关系。
 */
@Entity(
    tableName = "follow_relations",
    primaryKeys = ["followerId", "followeeId"],
    indices = [
        Index(value = ["followeeId"]),
        Index(value = ["followerId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["followerId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["followeeId"],
            onDelete = ForeignKey.CASCADE,
        )
    ]
)
data class FollowRelationEntity(
    val followerId: String,
    val followeeId: String,
    val createdAt: Long
)
