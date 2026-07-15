package com.trae.social.core.data.model

import kotlinx.serialization.Serializable

/**
 * 反哺力度权重（LLM 深度画像层输出，业务侧经 FeedbackController clamp 到 [0,0.8]）。
 *
 * 各字段对应一个反哺场景（见 issue #146 场景表）：
 * - topicBias：场景1 AI 推文主题选择
 * - accountPriority：场景2 发帖账号调度优先级
 * - interactionAffinity：场景3 AI 互动账号选择
 * - commentPersona：场景4 AI 评论内容生成
 * - feedBoost：场景5 信息流排序 boost
 * - followRecommend：场景6 关注/发现推荐
 * - personaCoEvolve：场景7 虚拟人设共演化
 * - interactionTiming：场景8 发布后互动排程时机
 *
 * 防信息茧房：clamp 上限 0.8 保留 >=20% 探索量。
 */
@Serializable
data class FeedbackWeights(
    val topicBias: Double = 0.0,
    val accountPriority: Double = 0.0,
    val interactionAffinity: Double = 0.0,
    val commentPersona: Double = 0.0,
    val feedBoost: Double = 0.0,
    val followRecommend: Double = 0.0,
    val personaCoEvolve: Double = 0.0,
    val interactionTiming: Double = 0.0
) {
    /** 全零权重（画像为空 / 采集关闭 / 降级时使用，业务侧零回归）。 */
    companion object {
        val ZERO = FeedbackWeights()

        /** 反哺场景编号 → 对应字段值。 */
        fun weightForScenario(scenarioId: Int, w: FeedbackWeights): Double = when (scenarioId) {
            1 -> w.topicBias
            2 -> w.accountPriority
            3 -> w.interactionAffinity
            4 -> w.commentPersona
            5 -> w.feedBoost
            6 -> w.followRecommend
            7 -> w.personaCoEvolve
            8 -> w.interactionTiming
            else -> 0.0
        }
    }
}
