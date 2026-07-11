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

    /**
     * RISK-15：LLM 调用统计——按 action 分组计数。
     */
    @Query("SELECT action, COUNT(*) as count FROM scheduler_logs GROUP BY action")
    suspend fun countByAction(): List<ActionCount>

    /**
     * RISK-15：LLM 调用统计——按 result 分组计数（成功/失败/限流等）。
     *
     * P2 修复：
     * 1. 使用 COALESCE(SUM, 0) 防止空表时 SUM 返回 NULL。
     * 2. 分类条件互斥：rate_limited 优先判定，避免与 error/failed 重复计数。
     * 3. LIKE 模式中的下划线转义：SQL LIKE 的 '_' 匹配任意单个字符，
     *    'rate_limited%' 中的下划线会被当作通配符，导致 'rateXlimited' 等误匹配。
     *    使用 ESCAPE '\' 将 '_' 转义为字面量，确保仅匹配 'rate_limited'。
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN result LIKE 'rate\_limited%' ESCAPE '\' THEN 1 ELSE 0 END), 0) as rateLimitedCount,
            COALESCE(SUM(CASE WHEN (result LIKE 'success%' OR result LIKE 'updated%' OR result LIKE 'published%') AND result NOT LIKE 'rate\_limited%' ESCAPE '\' THEN 1 ELSE 0 END), 0) as successCount,
            COALESCE(SUM(CASE WHEN (result LIKE '%error%' OR result LIKE '%failed%') AND result NOT LIKE 'rate\_limited%' ESCAPE '\' THEN 1 ELSE 0 END), 0) as errorCount,
            COUNT(*) as totalCount
        FROM scheduler_logs
        """
    )
    suspend fun getCallStatistics(): CallStatistics
}

/**
 * RISK-15：按 action 分组的计数结果。
 */
data class ActionCount(
    val action: String,
    val count: Int,
)

/**
 * RISK-15：LLM 调用统计聚合结果。
 */
data class CallStatistics(
    val successCount: Int,
    val errorCount: Int,
    val rateLimitedCount: Int,
    val totalCount: Int,
)
