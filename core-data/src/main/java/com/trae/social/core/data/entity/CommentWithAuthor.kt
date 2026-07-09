package com.trae.social.core.data.entity

/**
 * 评论 + 作者信息的联合查询结果（[CommentDao.getCommentsForTweet] 返回）。
 *
 * 通过 LEFT JOIN accounts 一次性带出作者展示名 / 用户名 / 头像 seed，
 * 避免 UI 层逐条查库。LEFT JOIN 容错：作者账号被删除时字段为 null（理论上
 * 因外键 CASCADE 不会发生，但保留兜底）。
 */
data class CommentWithAuthor(
    val id: String,
    val tweetId: String,
    val authorId: String,
    val authorName: String?,
    val authorUsername: String?,
    val authorAvatarSeed: String?,
    val content: String,
    val createdAt: Long
)
