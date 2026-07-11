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

    /**
     * 批量插入，主键/deduplicationKey 冲突时跳过（IGNORE）。
     *
     * 专用于种子数据幂等重导入：崩溃中断后重启时，已导入的文件重新导入不会
     * 因 unique deduplicationKey 冲突而整个事务回滚。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllOrIgnore(tweets: List<TweetEntity>)

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

    @Query("SELECT COUNT(*) FROM tweets WHERE authorId = :authorId AND createdAt >= :startOfDay AND isAiGenerated = 1")
    suspend fun countByAuthorSince(authorId: String, startOfDay: Long): Int

    /**
     * 统计某账号在 [windowStart, windowEnd) 时间窗内已发布的推文数。
     *
     * P1 修复：支撑 ScheduleRuleResolver 判断窗内推文数是否已达 postsPerWindow 上限。
     * #110：仅统计 AI 生成的推文，种子推文不消耗 AI 配额。
     */
    @Query("SELECT COUNT(*) FROM tweets WHERE authorId = :authorId AND createdAt >= :windowStart AND createdAt < :windowEnd AND isAiGenerated = 1")
    suspend fun countByAuthorInWindow(authorId: String, windowStart: Long, windowEnd: Long): Int

    @Query("SELECT COUNT(*) FROM tweets")
    suspend fun count(): Int

    @Query("UPDATE tweets SET likeCount = likeCount + :delta WHERE id = :tweetId")
    suspend fun updateLikeCount(tweetId: String, delta: Int)

    @Query("UPDATE tweets SET commentCount = commentCount + :delta WHERE id = :tweetId")
    suspend fun updateCommentCount(tweetId: String, delta: Int)

    @Query("UPDATE tweets SET retweetCount = retweetCount + :delta WHERE id = :tweetId")
    suspend fun updateRetweetCount(tweetId: String, delta: Int)

    /**
     * #138：按 ID 列表查询推文（用于个人主页 LIKES Tab 展示已点赞推文）。
     *
     * 空列表时返回空流，避免 Room 生成非法 SQL `IN ()`。
     */
    @Query("SELECT * FROM tweets WHERE id IN (:ids) ORDER BY createdAt DESC")
    fun observeByIds(ids: List<String>): Flow<List<TweetEntity>>
}
