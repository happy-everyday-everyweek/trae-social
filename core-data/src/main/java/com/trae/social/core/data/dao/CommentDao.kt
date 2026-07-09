package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trae.social.core.data.entity.CommentEntity
import com.trae.social.core.data.entity.CommentWithAuthor

/**
 * 评论数据访问对象。
 *
 * - [insert] 写入一条评论（REPLACE 策略，按主键去重）。
 * - [getCommentsForTweet] 按推文查询评论列表并 JOIN 账号信息，按时间升序展示。
 */
@Dao
interface CommentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(comment: CommentEntity)

    @Query(
        """
        SELECT c.id AS id, c.tweetId AS tweetId, c.authorId AS authorId,
               a.displayName AS authorName, a.username AS authorUsername,
               a.avatarSeed AS authorAvatarSeed,
               c.content AS content, c.createdAt AS createdAt
        FROM comments c
        LEFT JOIN accounts a ON a.id = c.authorId
        WHERE c.tweetId = :tweetId
        ORDER BY c.createdAt ASC
        """
    )
    suspend fun getCommentsForTweet(tweetId: String): List<CommentWithAuthor>
}
