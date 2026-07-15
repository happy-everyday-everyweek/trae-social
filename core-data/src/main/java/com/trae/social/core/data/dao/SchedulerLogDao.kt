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
     * 修复 #104 + #105：
     * 1. #104：`updated_40_rolledBack_0_failed_0` 同时匹配 `LIKE 'updated%'`（success）
     *    和 `LIKE '%failed%'`（error）导致双重计数。修复：error 仅匹配 `LIKE 'error%'`，
     *    不再用 `%failed%`（failed 是 result 中的子字段计数，非整体状态）。
     * 2. #105：`processed_*`、`scheduled_*` 未被分类。修复：加入 successCount。
     *    `skipped_*` 为信息性跳过，不计入 success 或 error。
     * 3. 分类条件互斥：rate_limited 优先判定。
     * 4. LIKE 模式中的下划线转义（#107 修复）。
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN result LIKE 'rate\_limited%' ESCAPE '\' THEN 1 ELSE 0 END), 0) as rateLimitedCount,
            COALESCE(SUM(CASE WHEN (result LIKE 'success%' OR result LIKE 'updated%' OR result LIKE 'published%' OR result LIKE 'processed%' OR result LIKE 'scheduled%') AND result NOT LIKE 'rate\_limited%' ESCAPE '\' THEN 1 ELSE 0 END), 0) as successCount,
            COALESCE(SUM(CASE WHEN result LIKE 'error%' AND result NOT LIKE 'rate\_limited%' ESCAPE '\' THEN 1 ELSE 0 END), 0) as errorCount,
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
