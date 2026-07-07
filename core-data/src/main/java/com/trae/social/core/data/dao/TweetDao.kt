package com.trae.social.core.data.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trae.social.core.data.entity.TweetEntity
import kotlinx.coroutines.flow.Flow

/**
 * 推文数据访问对象。
 *
 * 信息流按 createdAt DESC 分页；时间线查询带图推文。
 */
@Dao
interface TweetDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tweet: TweetEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(tweets: List<TweetEntity>)

    @Query("SELECT * FROM tweets ORDER BY createdAt DESC LIMIT :size OFFSET :offset")
    suspend fun getFeed(offset: Int, size: Int): List<TweetEntity>

    /**
     * Paging 3 数据源：按 createdAt DESC 流式分页。
     */
    @Query("SELECT * FROM tweets ORDER BY createdAt DESC")
    fun getFeedPagingSource(): PagingSource<Int, TweetEntity>

    @Query("SELECT * FROM tweets WHERE authorId = :authorId ORDER BY createdAt DESC")
    suspend fun getByAuthor(authorId: String): List<TweetEntity>

    @Query("SELECT * FROM tweets WHERE authorId = :authorId ORDER BY createdAt DESC")
    fun observeByAuthor(authorId: String): Flow<List<TweetEntity>>

    @Query("SELECT * FROM tweets WHERE mediaPath IS NOT NULL ORDER BY createdAt DESC")
    suspend fun getWithMedia(): List<TweetEntity>

    @Query("SELECT * FROM tweets WHERE mediaPath IS NOT NULL ORDER BY createdAt DESC")
    fun observeWithMedia(): Flow<List<TweetEntity>>

    @Query("SELECT * FROM tweets WHERE deduplicationKey = :key LIMIT 1")
    suspend fun getByDeduplicationKey(key: String): TweetEntity?

    @Query("SELECT * FROM tweets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TweetEntity?

    @Query("SELECT COUNT(*) FROM tweets WHERE authorId = :authorId AND createdAt >= :startOfDay")
    suspend fun countByAuthorSince(authorId: String, startOfDay: Long): Int

    @Query("UPDATE tweets SET likeCount = likeCount + :delta WHERE id = :tweetId")
    suspend fun updateLikeCount(tweetId: String, delta: Int)

    @Query("UPDATE tweets SET commentCount = commentCount + :delta WHERE id = :tweetId")
    suspend fun updateCommentCount(tweetId: String, delta: Int)

    @Query("UPDATE tweets SET retweetCount = retweetCount + :delta WHERE id = :tweetId")
    suspend fun updateRetweetCount(tweetId: String, delta: Int)
}
