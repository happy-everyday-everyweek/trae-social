package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户配置实体（KV 结构）。
 *
 * 用于持久化需要在数据库层面查询的配置项。
 * 敏感数据（API Key）不走此表，统一通过 EncryptedSharedPreferences 存储（RISK-11）。
 */
@Entity(tableName = "user_configs")
data class UserConfigEntity(
    @PrimaryKey
    val key: String,
    val value: String
)
