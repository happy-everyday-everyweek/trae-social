package com.trae.social.core.data

/**
 * 推文相关长度限制（#285：消除跨模块 280 字面量重复）。
 */
object TweetLimits {
    /** 推文最大长度（与 Twitter/X 一致）。 */
    const val MAX_TWEET_LENGTH = 280
    /** 发布页配文最大长度（与推文长度一致）。 */
    const val MAX_CAPTION_LENGTH = 280
}
