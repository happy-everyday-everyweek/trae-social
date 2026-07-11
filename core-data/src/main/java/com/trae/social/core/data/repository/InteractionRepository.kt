package com.trae.social.core.data.repository

import com.trae.social.core.data.dao.InteractionDao
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.InteractionType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 互动仓库：排程互动、查询待执行互动。
 */
@Singleton
class InteractionRepository @Inject constructor(
    private val interactionDao: InteractionDao
) {

    suspend fun scheduleInteraction(interaction: InteractionEntity) =
        interactionDao.insert(interaction)

    suspend fun scheduleInteractions(interactions: List<InteractionEntity>) =
        interactionDao.insertAll(interactions)

    // #79：透传 batch limit，控制单次拉取的待执行互动数量
    suspend fun getPendingInteractions(now: Long, limit: Int = 50): List<InteractionEntity> =
        interactionDao.getPendingBefore(now, limit)

    fun observePendingInteractions(now: Long): Flow<List<InteractionEntity>> =
        interactionDao.observePendingBefore(now)

    suspend fun markExecuted(interactionId: String, executedAt: Long) =
        interactionDao.markExecuted(interactionId, executedAt)

    /**
     * 原子地执行一批互动并更新推文计数（IMPL-6）。
     */
    suspend fun executeInteractionsAndUpdateTweet(
        interactions: List<InteractionEntity>,
        executedAt: Long,
        tweetId: String,
    ) = interactionDao.executeInteractionsAndUpdateTweet(interactions, executedAt, tweetId)

    suspend fun countExecutedByType(tweetId: String, type: InteractionType): Int =
        interactionDao.countExecutedByType(tweetId, type)

    // #103：查询某账号已点赞的推文 ID 列表，替代 likeCount > 0 启发式
    suspend fun getLikedTweetIdsByAccount(accountId: String): List<String> =
        interactionDao.getLikedTweetIdsByAccount(accountId)
}
