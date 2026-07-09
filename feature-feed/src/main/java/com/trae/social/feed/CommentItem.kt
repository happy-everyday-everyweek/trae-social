package com.trae.social.feed

/**
 * 评论展示项：评论弹层列表使用。
 *
 * 由 [com.trae.social.feed.FeedViewModel.loadComments] 从持久化评论表加载映射而来；
 * 用户发送评论后也构造同类型实例追加到列表，保证加载与即时追加的展示一致。
 */
data class CommentItem(
    val id: String,
    val authorName: String,
    val authorAvatarSeed: String,
    val content: String,
    val createdAt: Long,
)
