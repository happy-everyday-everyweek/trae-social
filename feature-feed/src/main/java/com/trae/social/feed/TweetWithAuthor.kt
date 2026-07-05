package com.trae.social.feed

import com.trae.social.core.data.entity.TweetEntity

/**
 * 推文 + 作者信息的联合视图模型。
 *
 * TweetEntity 仅持有 authorId，UI 层需要 displayName / username / avatarSeed，
 * 通过该数据类在 ViewModel 层 join 后下发，避免在 Composable 中再次查库。
 */
data class TweetWithAuthor(
    val tweet: TweetEntity,
    val authorName: String,
    val authorUsername: String,
    val authorAvatarSeed: String
)
