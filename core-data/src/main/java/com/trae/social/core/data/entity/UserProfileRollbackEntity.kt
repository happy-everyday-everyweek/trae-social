package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 画像版本回滚历史（审计）。
 *
 * 每次 [com.trae.social.core.profiling.version.ProfileVersionStore.applyRollback] 写一条记录，
 * 保留完整回滚链，DevOptionsScreen 可查看。
 *
 * - [fromVersionId] 回滚前的激活版本。
 * - [toVersionId] 回滚后激活的版本。
 * - [reason] 触发回滚的用户消息原文。
 */
@Entity(
    tableName = "user_profile_rollbacks",
    indices = [Index(value = ["appliedAt"])]
)
data class UserProfileRollbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromVersionId: Long,
    val toVersionId: Long,
    val reason: String,
    val appliedAt: Long
)
