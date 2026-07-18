package com.trae.social.core.data.repository

import com.trae.social.core.data.dao.CommentDao
import com.trae.social.core.data.entity.CommentEntity
import com.trae.social.core.data.entity.CommentWithAuthor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 评论仓库：写入评论、按推文查询评论列表（带作者信息）。
 *
 * 评论持久化于独立的 comments 表（见 [CommentEntity]），评论弹层打开时通过
 * [getCommentsForTweet] 加载展示。
 */
@Singleton
class CommentRepository @Inject constructor(
    private val commentDao: CommentDao
) {

    suspend fun addComment(comment: CommentEntity) = commentDao.insert(comment)

    suspend fun getCommentsForTweet(tweetId: String): List<CommentWithAuthor> =
        commentDao.getCommentsForTweet(tweetId)

    /**
     * #146：按推文 + 作者查询评论原文列表（供 EventTextPreParser 回查用户评论文本）。
     */
    suspend fun getByTweetAndAuthor(tweetId: String, authorId: String): List<CommentEntity> =
        commentDao.getByTweetAndAuthor(tweetId, authorId)

    /**
     * #146 review：按评论 ID 查询单条评论原文（供 EventTextPreParser 精确回查）。
     */
    suspend fun getById(commentId: String): CommentEntity? = commentDao.getById(commentId)
}
