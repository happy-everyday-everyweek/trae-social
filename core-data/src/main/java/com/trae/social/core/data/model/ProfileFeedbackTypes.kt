package com.trae.social.core.data.model

import kotlinx.serialization.Serializable

/**
 * 回滚预览（差异对比，不直接应用，需用户在对话中确认）。
 */
@Serializable
data class RollbackPreview(
    val targetVersionId: Long,
    val targetCreatedAt: Long,
    val targetNarrative: String,
    val currentNarrative: String,
    val feedbackWeightsDiff: Map<String, DoubleDelta>,
    val overridesToPreserve: List<OverrideRecord>,
    val affectedScenarios: List<Int>
)

/** 数值变化量。 */
@Serializable
data class DoubleDelta(val from: Double, val to: Double) {
    val delta: Double get() = to - from
}

/** 回滚应用结果。 */
@Serializable
data class RollbackResult(
    val fromVersionId: Long,
    val toVersionId: Long,
    val appliedAt: Long
)

/** 回滚历史记录（审计）。 */
@Serializable
data class RollbackRecord(
    val id: Long,
    val fromVersionId: Long,
    val toVersionId: Long,
    val reason: String,
    val appliedAt: Long
)

/** 版本摘要（供智能体 prompt 与 DevOptions 展示）。 */
@Serializable
data class VersionSummary(
    val id: Long,
    val createdAt: Long,
    val narrativePreview: String,
    val isActive: Boolean
)

/**
 * LLM 画像 Prompt 输入摘要（分层聚合，token 预算 <=2K，用户反馈优先级最高）。
 */
@Serializable
data class EventSummary(
    val totalEvents: Int,
    val topThemes: List<ThemeEvidence>,
    val topActiveHours: List<HourEvidence>,
    val interactionRates: InteractionTendency,
    val postingCadenceSummary: String,
    val periodicitySummary: String
)

/** 反哺效果（A/B 对照，各场景 driven/control 差异）。 */
@Serializable
data class FeedbackEffect(
    val scenarioDeltas: Map<Int, Double>,
    val negativeScenarios: List<Int>
)

/** 用户反馈摘要（最近 N 条消息 + 已应用覆盖）。 */
@Serializable
data class UserFeedbackSummary(
    val recentMessages: List<FeedbackMessageSummary>,
    val activeOverrides: List<OverrideRecord>
)

@Serializable
data class FeedbackMessageSummary(
    val role: String,
    val content: String,
    val createdAt: Long
)

/** 智能体回复。 */
@Serializable
data class AgentReply(
    val text: String,
    val appliedActions: List<OverrideRecord>,
    val rollbackPreviews: List<RollbackPreview>,
    val needsClarification: Boolean = false,
    val clarificationQuestion: String? = null,
    val degraded: Boolean = false
)
