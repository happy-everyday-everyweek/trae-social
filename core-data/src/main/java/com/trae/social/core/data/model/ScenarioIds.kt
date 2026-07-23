package com.trae.social.core.data.model

/**
 * 反哺层场景 ID 共享常量（#312）。
 *
 * 场景 ID（1-8）此前作为裸 Int 字面量散落在 6+ 文件（TweetGenerationWorker /
 * InteractionWorker / PersonaUpdateWorker / FeedViewModel / FollowListViewModel /
 * UserProfileAggregator 等），无共享枚举或常量，属 Hyrum's Law 隐式契约——
 * 任一处写错数字无编译错误，但会导致 A/B 回测打标/查询错位。
 *
 * `FeedbackController.shouldApply` 签名为 `Int`，为最小侵入性，此处用命名 Int 常量
 * 而非枚举（避免改签名 + 全调用点加 `.id`）。调用方将 `shouldApply(5, ...)` 改为
 * `shouldApply(ScenarioIds.FEED_BOOST, ...)` 即可。
 *
 * 放在 core-data 模块（而非 core-profiling），因为 [FeedbackAction.sanitize] 的
 * 值域校验 `scenarioId !in [ALL]` 也在此模块，core-data 不能反向依赖 core-profiling。
 *
 * 值域校验 [1, 8] 仍在 [sanitize] 中保留，因为外部 LLM 可能下发任意 Int，需在入口校验。
 */
object ScenarioIds {
    /** 场景 1：topicBias — 推文主题偏置（TweetGenerationWorker） */
    const val TOPIC_BIAS: Int = 1

    /** 场景 2：accountPriority — 账号优先级（预留，无消费方） */
    const val ACCOUNT_PRIORITY: Int = 2

    /** 场景 3：interactionAffinity — 互动账号选择（InteractionWorker） */
    const val INTERACTION_AFFINITY: Int = 3

    /** 场景 4：commentPersona — 评论文本驱动（InteractionWorker + CommentPromptBuilder） */
    const val COMMENT_PERSONA: Int = 4

    /** 场景 5：feedBoost — Feed 流加权重排（FeedViewModel） */
    const val FEED_BOOST: Int = 5

    /** 场景 6：followRecommend — 关注推荐（FollowListViewModel） */
    const val FOLLOW_RECOMMEND: Int = 6

    /** 场景 7：personaCoEvolve — 人设共演化（PersonaUpdateWorker） */
    const val PERSONA_CO_EVOLVE: Int = 7

    /** 场景 8：interactionTiming — 互动时段（InteractionWorker） */
    const val INTERACTION_TIMING: Int = 8

    /** 全部场景 ID，供遍历 / 值域校验时使用（替代裸 `1..8`）。 */
    val ALL: IntRange = 1..8
}
