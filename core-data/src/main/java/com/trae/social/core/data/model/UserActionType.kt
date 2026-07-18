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
    FEEDBACK_VERSION_ROLLBACK_APPLIED("feedback");

    companion object {
        fun fromName(name: String): UserActionType? =
            runCatching { valueOf(name) }.getOrNull()
    }
}
