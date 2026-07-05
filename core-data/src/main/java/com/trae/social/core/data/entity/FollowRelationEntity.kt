package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 关注关系实体。复合主键 (followerId, followeeId)。
 */
@Entity(
    tableName = "follow_relations",
    primaryKeys = ["followerId", "followeeId"],
    indices = [
        Index(value = ["followeeId"]),
        Index(value = ["followerId"])
    ]
)
data class FollowRelationEntity(
    val followerId: String,
    val followeeId: String,
    val createdAt: Long
)
