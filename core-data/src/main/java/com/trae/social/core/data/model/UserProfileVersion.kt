package com.trae.social.core.data.model

import kotlinx.serialization.Serializable

/**
 * LLM 深度画像版本（领域模型，落库 payload 为其 JSON 序列化）。
 *
 * 所有版本永久保留，回滚 = 激活旧版本，不删除新版本。
 */
@Serializable
data class UserProfileVersion(
    val id: Long = 0,
    val identityHypothesis: String,
    val personalityTraits: List<String>,
    val contentPreferences: List<String>,
    val socialStyle: String,
    val activityProfile: String,
    val engagementLevel: String,
    val feedbackWeights: FeedbackWeights,
    val narrative: String,
    val overrideAcknowledgment: List<String> = emptyList(),
    val modelProvider: String,
    val promptHash: String,
    val inputFingerprint: String,
    val snapshotId: Long? = null,
    val rollbackFrom: Long? = null,
    val isActive: Boolean = false,
    val createdAt: Long
)

/**
 * 用户显式覆盖记录（领域模型，用户掌控层）。
 *
 * 覆盖不衰减，直到用户撤销或 reset；同 key 冲突 supersede 旧覆盖（软删除保留审计）。
 */
@Serializable
data class OverrideRecord(
    val id: Long = 0,
    val type: OverrideType,
    val key: String,
    val value: String,
    val reason: String,
    val createdAt: Long,
    val source: String,
    val superseded: Boolean = false
)

/** 覆盖类型。 */
enum class OverrideType(val id: String) {
    THEME_BOOST("THEME_BOOST"),
    THEME_SUPPRESS("THEME_SUPPRESS"),
    SCENARIO_DISABLE("SCENARIO_DISABLE"),
    SET_ACTIVE_HOURS("SET_ACTIVE_HOURS"),
    ADD_PREFERENCE("ADD_PREFERENCE"),
    REMOVE_PREFERENCE("REMOVE_PREFERENCE"),
    CORRECT_NARRATIVE("CORRECT_NARRATIVE");

    companion object {
        fun fromId(id: String): OverrideType? = values().find { it.id == id }
    }
}
