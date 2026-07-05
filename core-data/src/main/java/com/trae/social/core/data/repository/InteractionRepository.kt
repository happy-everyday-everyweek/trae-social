package com.trae.social.core.data.repository

import com.trae.social.core.data.dao.InteractionDao
import com.trae.social.core.data.entity.InteractionEntity
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

    suspend fun getPendingInteractions(now: Long): List<InteractionEntity> =
        interactionDao.getPendingBefore(now)

    fun observePendingInteractions(now: Long): Flow<List<InteractionEntity>> =
        interactionDao.observePendingBefore(now)

    suspend fun markExecuted(interactionId: String, executedAt: Long) =
        interactionDao.markExecuted(interactionId, executedAt)
}
