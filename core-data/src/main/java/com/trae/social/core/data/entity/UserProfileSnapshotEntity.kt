package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 基础分析画像快照（版本化）。
 *
 * 由 [com.trae.social.core.data.dao.UserActionDao] 邻接的
 * [com.trae.social.core.data.dao.UserProfileDao] 持久化。
 *
 * - [payload] 为 [com.trae.social.core.profiling.model.UserProfileSnapshot] 的 JSON 序列化。
 * - [source] 取值：INCREMENTAL（增量合并）/ FULL_RECOMPUTE（全量重算兜底，6h）/
 *   COLD_START_SEEDING（onboarding 兴趣选择冷启动写入）。
 */
@Entity(
    tableName = "user_profile_snapshots",
    indices = [Index(value = ["computedAt"])]
)
data class UserProfileSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payload: String,
    val eventWindowStart: Long,
    val eventWindowEnd: Long,
    val computedAt: Long,
    val source: String
)
