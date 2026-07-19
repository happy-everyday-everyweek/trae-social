package com.trae.social.feed

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import com.trae.social.core.data.entity.TweetEntity

/**
 * 推文 + 作者信息的联合视图模型。
 *
 * TweetEntity 仅持有 authorId，UI 层需要 displayName / username / avatarSeed，
 * 通过该数据类在 ViewModel 层 join 后下发，避免在 Composable 中再次查库。
 *
 * #212：实现 [Saver]，使 FeedScreen 的 commentTarget / retweetTarget 在屏幕旋转或
 * 系统回收后可被 rememberSaveable 恢复，避免已打开的弹层被意外关闭。
 */
data class TweetWithAuthor(
    val tweet: TweetEntity,
    val authorName: String,
    val authorUsername: String,
    val authorAvatarSeed: String
) {
    companion object {
        /**
         * #212：将 TweetWithAuthor 序列化为 Bundle 可保存的 Map<String, Any?>，
         * 使用 key 而非位置索引，避免字段重排导致的保存/恢复错位。
         */
        val Saver: Saver<TweetWithAuthor, Any> = mapSaver(
            save = {
                val t = it.tweet
                mapOf(
                    "id" to t.id,
                    "authorId" to t.authorId,
                    "text" to t.text,
                    "mediaPath" to t.mediaPath,
                    "mediaTheme" to t.mediaTheme,
                    "createdAt" to t.createdAt,
                    "likeCount" to t.likeCount,
                    "commentCount" to t.commentCount,
                    "retweetCount" to t.retweetCount,
                    "isAiGenerated" to t.isAiGenerated,
                    "deduplicationKey" to t.deduplicationKey,
                    "authorName" to it.authorName,
                    "authorUsername" to it.authorUsername,
                    "authorAvatarSeed" to it.authorAvatarSeed,
                )
            },
            restore = { map ->
                TweetWithAuthor(
                    tweet = TweetEntity(
                        id = map["id"] as String,
                        authorId = map["authorId"] as String,
                        text = map["text"] as String,
                        mediaPath = map["mediaPath"] as String?,
                        mediaTheme = map["mediaTheme"] as String?,
                        createdAt = map["createdAt"] as Long,
                        likeCount = map["likeCount"] as Int,
                        commentCount = map["commentCount"] as Int,
                        retweetCount = map["retweetCount"] as Int,
                        isAiGenerated = map["isAiGenerated"] as Boolean,
                        deduplicationKey = map["deduplicationKey"] as String,
                    ),
                    authorName = map["authorName"] as String,
                    authorUsername = map["authorUsername"] as String,
                    authorAvatarSeed = map["authorAvatarSeed"] as String,
                )
            },
        )
    }
}
