package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 人设动态字段实体（AI 周期性维护）。
 *
 * 与 [AccountEntity] 的动态摘要字段互补：本表存储结构化的详细动态数据。
 *
 * @param relationshipNetwork 关系网络（TypeConverter 序列化为 JSON）
 */
@Entity(tableName = "persona_dynamic_fields")
data class PersonaDynamicFieldEntity(
    @PrimaryKey
    val accountId: String,
    val lifeStory: String,
    val workInfo: String,
    val relationshipNetwork: List<String>,
    val mood: String,
    val updatedAt: Long
)
