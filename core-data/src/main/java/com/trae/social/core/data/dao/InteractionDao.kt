package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trae.social.core.data.entity.InteractionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 互动数据访问对象。
 *
 * 调度排程：scheduleInteraction 写入 scheduledAt；
 * 调度执行：getPendingBefore 拉取到期互动，markExecuted 标记完成。
 */
@Dao
interface InteractionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(interaction: InteractionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(interactions: List<InteractionEntity>)

    @Query("SELECT * FROM interactions WHERE scheduledAt <= :time AND executedAt IS NULL ORDER BY scheduledAt ASC")
    suspend fun getPendingBefore(time: Long): List<InteractionEntity>

    @Query("SELECT * FROM interactions WHERE scheduledAt <= :time AND executedAt IS NULL ORDER BY scheduledAt ASC")
    fun observePendingBefore(time: Long): Flow<List<InteractionEntity>>

    @Query("UPDATE interactions SET executedAt = :executedAt WHERE id = :id")
    suspend fun markExecuted(id: String, executedAt: Long)

    @Query("SELECT COUNT(*) FROM interactions WHERE tweetId = :tweetId AND type = :type AND executedAt IS NOT NULL")
    suspend fun countExecutedByType(tweetId: String, type: String): Int
}
