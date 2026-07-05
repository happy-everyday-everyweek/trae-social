package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trae.social.core.data.entity.SchedulerLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * 调度日志数据访问对象（RISK-15：可观测性）。
 */
@Dao
interface SchedulerLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: SchedulerLogEntity): Long

    @Query("SELECT * FROM scheduler_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SchedulerLogEntity>

    @Query("SELECT * FROM scheduler_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SchedulerLogEntity>>

    @Query("SELECT * FROM scheduler_logs WHERE accountId = :accountId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByAccount(accountId: String, limit: Int): List<SchedulerLogEntity>

    @Query("DELETE FROM scheduler_logs WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long): Int
}
