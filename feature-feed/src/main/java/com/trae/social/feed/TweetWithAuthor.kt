package com.trae.social.feed

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
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
         * #212：将 TweetWithAuthor 展平为 Bundle 可保存的 List<Any?>，
         * 字段顺序与 [TweetEntity] 构造参数一致，末尾追加作者元信息。
         */
        val Saver: Saver<TweetWithAuthor, Any> = listSaver(
            save = {
                val t = it.tweet
                listOf(
                    t.id,
                    t.authorId,
                    t.text,
                    t.mediaPath,
                    t.mediaTheme,
                    t.createdAt,
                    t.likeCount,
                    t.commentCount,
                    t.retweetCount,
                    t.isAiGenerated,
                    t.deduplicationKey,
                    it.authorName,
                    it.authorUsername,
                    it.authorAvatarSeed,
                )
            },
            restore = { items ->
                TweetWithAuthor(
                    tweet = TweetEntity(
                        id = items[0] as String,
                        authorId = items[1] as String,
                        text = items[2] as String,
                        mediaPath = items[3] as String?,
                        mediaTheme = items[4] as String?,
                        createdAt = items[5] as Long,
                        likeCount = items[6] as Int,
                        commentCount = items[7] as Int,
                        retweetCount = items[8] as Int,
                        isAiGenerated = items[9] as Boolean,
                        deduplicationKey = items[10] as String,
                    ),
                    authorName = items[11] as String,
                    authorUsername = items[12] as String,
                    authorAvatarSeed = items[13] as String,
                )
            },
        )
    }
}
