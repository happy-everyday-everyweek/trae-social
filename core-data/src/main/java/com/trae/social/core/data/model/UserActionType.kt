package com.trae.social.core.data.model

/**
 * 用户行为事件类型枚举（#146 捕获层）。
 *
 * 持久化时以 [name] 存入 user_action_events.type 列。
 */
enum class UserActionType(val category: String) {
    // 会话
    SESSION_START("session"),
    SESSION_END("session"),

    // 浏览/停留
    SCREEN_ENTER("browse"),
    SCREEN_LEAVE("browse"),
    TWEET_VIEW("browse"),
    TWEET_DWELL("browse"),
    IMAGE_FULLSCREEN("browse"),

    // 互动
    TWEET_LIKE("interaction"),
    TWEET_UNLIKE("interaction"),
    TWEET_COMMENT("interaction"),
    TWEET_RETWEET("interaction"),
    TWEET_BOOKMARK("interaction"),
    TWEET_UNBOOKMARK("interaction"),

    // 社交关系
    FOLLOW("social"),
    UNFOLLOW("social"),

    // 发布
    PUBLISH_TWEET("publish"),
    CAPTURE_PHOTO("publish"),
    APPLY_FILTER("publish"),
    PUBLISH_MODE_SWITCH("publish"),

    // 导航
    TAB_SWITCH("navigation"),
    OPEN_SETTINGS("navigation"),
    OPEN_APIKEY("navigation"),
    OPEN_DEVOPTIONS("navigation"),
    OPEN_FOLLOWLIST("navigation"),
    OPEN_PROFILE_CHAT("navigation"),

    // 引导
    ONBOARDING_INTEREST_SELECTED("onboarding"),
    ONBOARDING_STEP("onboarding"),
    ONBOARDING_COMPLETE("onboarding"),
    ONBOARDING_SKIP("onboarding"),

    // 用户反馈（用户掌控层）
    FEEDBACK_MESSAGE_SENT("feedback"),
    FEEDBACK_OVERRIDE_APPLIED("feedback"),
    FEEDBACK_OVERRIDE_RESET("feedback"),
    FEEDBACK_VERSION_ROLLBACK_PREVIEW("feedback"),
    FEEDBACK_VERSION_ROLLBACK_APPLIED("feedback"),

    // 反哺层 A/B 闭环（#146 第六轮 review B1/B2 修复）
    // INTERACTION_SCHEDULED：调度器为 driven/control 排程打标事件（非真实用户行为），
    //   仅用于 computeFeedbackEffect 统计 driven/control 两组曝光配额；不参与用户画像
    //   统计（BasicProfileAnalyzer.analyze 入口过滤），不污染 INTERACTION_TYPES。
    // SCENARIO_OUTCOME：用户对先前被调度器打标的目标产生真实互动（like/comment/retweet/
    //   bookmark）时，由 FeedViewModel 查最近 INTERACTION_SCHEDULED 事件归因后发出，
    //   extra 携带 scenarioId/drivenByProfile/group，供 computeScenarioStats 计算
    //   drivenRate - controlRate = delta。这是 A/B 反哺闭环的 outcome 信号源。
    INTERACTION_SCHEDULED("scenario_ab"),
    SCENARIO_OUTCOME("scenario_ab");

    companion object {
        fun fromName(name: String): UserActionType? =
            runCatching { valueOf(name) }.getOrNull()
    }
}
