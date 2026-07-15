package com.trae.social.core.data.model

import kotlinx.serialization.Serializable

/**
 * 基础分析画像快照（无 LLM，纯轻量算法计算）。
 *
 * 每维度附置信度 [ProfileConfidence]，业务侧读取时低置信度维度反哺权重乘以 confidence，
 * 实现"画像越准干预越强，低置信度自动降权"，避免弱数据强干预。
 */
@Serializable
data class UserProfileSnapshot(
    val activeHours: List<Int>,
    val interestVector: Map<String, Double>,
    val interactionTendency: InteractionTendency,
    val browseDepth: BrowseDepth,
    val postingCadence: PostingCadence,
    val socialStyle: SocialStyle,
    val periodicity: Periodicity,
    val confidence: ProfileConfidence,
    val evidence: ProfileEvidence,
    val computedAt: Long,
    val eventWindowStart: Long,
    val eventWindowEnd: Long
)

/** 互动倾向（占 viewed 总数的比率，0-1）。 */
@Serializable
data class InteractionTendency(
    val likeRate: Double,
    val commentRate: Double,
    val retweetRate: Double,
    val bookmarkRate: Double
)

/** 浏览深度。 */
@Serializable
data class BrowseDepth(
    val avgDwellMs: Long,
    val tweetsPerSession: Double,
    val scrollDepthRatio: Double
)

/** 发帖节奏。 */
@Serializable
data class PostingCadence(
    val postFrequency: Double,
    val postingHours: List<Int>,
    val avgCaptionLength: Int,
    val avgImageCount: Double
)

/** 社交风格。 */
@Serializable
data class SocialStyle(
    val activeFollowRatio: Double,
    val avgInteractionDelayMs: Long
)

/** 周期性特征（工作日 vs 周末活跃差异）。 */
@Serializable
data class Periodicity(
    val weekdayScore: Double,
    val weekendScore: Double,
    val isWeekendDominant: Boolean
)

/** 各维度置信度 [0,1]，覆盖天数/事件数越多越高。 */
@Serializable
data class ProfileConfidence(
    val activeHours: Double,
    val interestVector: Double,
    val interactionTendency: Double,
    val browseDepth: Double,
    val postingCadence: Double,
    val socialStyle: Double,
    val periodicity: Double
) {
    /** 整体置信度（各维度均值），用于概览展示。 */
    val overall: Double get() = listOf(
        activeHours, interestVector, interactionTendency,
        browseDepth, postingCadence, socialStyle, periodicity
    ).average()
}

/** 证据链（解释性，DevOptionsScreen 展示）。 */
@Serializable
data class ProfileEvidence(
    val eventCount: Int,
    val anomalyCount: Int,
    val topThemes: List<ThemeEvidence>,
    val topActiveHours: List<HourEvidence>,
    val sampleTweetIds: List<String>
)

@Serializable
data class ThemeEvidence(
    val theme: String,
    val weight: Double,
    val viewed: Int,
    val interactions: Int
)

@Serializable
data class HourEvidence(
    val hour: Int,
    val eventCount: Int,
    val weight: Double
)
