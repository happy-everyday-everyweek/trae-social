package com.trae.social.core.profiling.analysis

/**
 * 画像分析层共享常量（#310）。
 *
 * [SCHEDULER_SCREENS] 此前在 [BasicProfileAnalyzer] 与 [EventTextPreParser] 中各自
 * 私有定义且附带"与 XXX 一致"注释，任一处修改需同步多处（DRY 违反）。抽到此 object
 * 后统一引用。
 */
object AnalysisConstants {
    /**
     * 调度器 Worker 落事件的 screen 白名单。
     *
     * 用于区分"调度器打标事件"与"真实用户行为事件"：
     * - [BasicProfileAnalyzer] 据此过滤调度器事件，避免污染用户画像
     * - [EventTextPreParser] 据此跳过调度器事件，不送 LLM 解析
     *
     * 新增 Worker 落事件时若使用新的 screen 值，需同步加入此集合。
     */
    val SCHEDULER_SCREENS: Set<String> = setOf(
        "tweet_generation",
        "interaction_schedule",
        "interaction_schedule_comment",
        "persona_update_co_evolve",
    )
}
