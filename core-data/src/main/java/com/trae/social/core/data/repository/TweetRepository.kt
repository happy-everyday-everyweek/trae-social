package com.trae.social.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.trae.social.core.data.dao.TweetDao
import com.trae.social.core.data.entity.TweetEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 推文仓库：信息流分页、推文写入、时间线（带图）查询。
 */
@Singleton
class TweetRepository @Inject constructor(
    private val tweetDao: TweetDao
) {

    /**
     * 信息流分页数据流（Paging 3），按 createdAt DESC。
     */
    fun getFeedFlow(): Flow<PagingData<TweetEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE / 2,
                enablePlaceholders = false,
                initialLoadSize = PAGE_SIZE
            ),
            pagingSourceFactory = { tweetDao.getFeedPagingSource() }
        ).flow
    }

    suspend fun insertTweet(tweet: TweetEntity) = tweetDao.insert(tweet)

    suspend fun insertAll(tweets: List<TweetEntity>) = tweetDao.insertAll(tweets)

    suspend fun getByAuthor(authorId: String): List<TweetEntity> = tweetDao.getByAuthor(authorId)

    /**
     * #177：按账号查询最近 [limit] 条推文。LIMIT 下推到 SQL 层，避免全量加载后截断。
     */
    suspend fun getByAuthorLimit(authorId: String, limit: Int): List<TweetEntity> =
        tweetDao.getByAuthorLimit(authorId, limit)

    fun observeByAuthor(authorId: String): Flow<List<TweetEntity>> =
        tweetDao.observeByAuthor(authorId)

    suspend fun getMediaTweets(): List<TweetEntity> = tweetDao.getWithMedia()

    fun observeMediaTweets(): Flow<List<TweetEntity>> = tweetDao.observeWithMedia()

    suspend fun getByDeduplicationKey(key: String): TweetEntity? =
        tweetDao.getByDeduplicationKey(key)

    suspend fun getById(id: String): TweetEntity? = tweetDao.getById(id)

    suspend fun countByAuthorSince(authorId: String, startOfDay: Long): Int =
        tweetDao.countByAuthorSince(authorId, startOfDay)

    /**
     * 统计某账号在 [windowStart, windowEnd) 时间窗内已发布的推文数。
     *
     * P1 修复：支撑 ScheduleRuleResolver 判断窗内推文数是否已达 postsPerWindow 上限。
     */
    suspend fun countByAuthorInWindow(authorId: String, windowStart: Long, windowEnd: Long): Int =
        tweetDao.countByAuthorInWindow(authorId, windowStart, windowEnd)

    suspend fun updateLikeCount(tweetId: String, delta: Int) =
        tweetDao.updateLikeCount(tweetId, delta)

    suspend fun updateCommentCount(tweetId: String, delta: Int) =
        tweetDao.updateCommentCount(tweetId, delta)

    suspend fun updateRetweetCount(tweetId: String, delta: Int) =
        tweetDao.updateRetweetCount(tweetId, delta)

    /**
     * #138：按 ID 列表观察推文（用于个人主页 LIKES Tab）。
     */
    fun observeByIds(ids: List<String>): Flow<List<TweetEntity>> =
        if (ids.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
        else tweetDao.observeByIds(ids)

    companion object {
        const val PAGE_SIZE = 20
    }
}
