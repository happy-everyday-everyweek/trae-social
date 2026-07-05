package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 配图使用记录实体（配图去重用）。
 *
 * 同一账号 30 天内不重复使用同一图片（基于 accountId + imageHash）。
 */
@Entity(
    tableName = "image_usages",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["imageHash"]),
        Index(value = ["usedAt"])
    ]
)
data class ImageUsageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: String,
    val imageHash: String,
    val usedAt: Long
)
