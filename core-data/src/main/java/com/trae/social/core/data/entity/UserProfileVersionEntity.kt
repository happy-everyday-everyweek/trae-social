package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * LLM 深度画像版本（含反哺权重、覆盖回应、输入指纹、激活标记）。
 *
 * 所有版本永久保留（受 maxProfileVersions 上限保护），回滚 = 激活旧版本，
 * 不删除新版本，保留完整审计链。
 *
 * - [payload] 为 [com.trae.social.core.profiling.model.UserProfileVersion] 的 JSON 序列化，
 *   含 identityHypothesis / feedbackWeights / overrideAcknowledgment 等。
 * - [isActive] 标记当前激活版本：Worker 产生新版本时设 true 并取消其他；
 *   回滚时设目标版本 true 并取消当前。业务侧读激活版本反哺。
 * - [rollbackFrom] 非空表示该版本是回滚产生的（指向触发回滚的源版本），便于审计。
 * - [inputFingerprint] 用于输入指纹缓存复用，避免相同输入重复消耗 LLM 配额。
 */
@Entity(
    tableName = "user_profile_versions",
    indices = [
        Index(value = ["snapshotId"]),
        Index(value = ["createdAt"]),
        Index(value = ["inputFingerprint"]),
        Index(value = ["isActive"])
    ]
)
data class UserProfileVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payload: String,
    val narrative: String,
    val modelProvider: String,
    val promptHash: String,
    val inputFingerprint: String,
    val snapshotId: Long?,
    val rollbackFrom: Long?,
    val isActive: Boolean = false,
    val createdAt: Long
)
