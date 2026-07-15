package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trae.social.core.data.entity.UserActionEventEntity

/**
 * 单场景 A/B 反哺效果统计（driven vs control）。
 *
 * - [drivenRate]：driven 组（drivenByProfile=true）的互动率。
 * - [controlRate]：control 组的互动率。
 * - [delta] = drivenRate - controlRate，<0 表示反哺负反馈，下一轮降低该场景权重。
 */
data class ScenarioEffectStats(
    val scenarioId: Int,
    val drivenCount: Int,
    val controlCount: Int,
    val drivenRate: Double,
    val controlRate: Double,
    val delta: Double
)

/**
 * 用户行为原始事件 DAO。
 */
@Dao
interface UserActionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: UserActionEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<UserActionEventEntity>)

    @Query("SELECT * FROM user_action_events WHERE occurredAt BETWEEN :start AND :end ORDER BY occurredAt ASC")
    suspend fun queryBetween(start: Long, end: Long): List<UserActionEventEntity>

    @Query("SELECT * FROM user_action_events WHERE type = :type AND occurredAt >= :since ORDER BY occurredAt ASC")
    suspend fun queryByType(type: String, since: Long): List<UserActionEventEntity>

    @Query("SELECT COUNT(*) FROM user_action_events WHERE occurredAt >= :ts")
    suspend fun countSince(ts: Long): Int

    @Query("DELETE FROM user_action_events WHERE occurredAt < :ts")
    suspend fun deleteBefore(ts: Long): Int

    @Query("SELECT COUNT(*) FROM user_action_events")
    suspend fun countAll(): Int

    @Query("DELETE FROM user_action_events")
    suspend fun deleteAll()

    /**
     * 计算指定场景 driven/control 两组的反馈差异（A/B 回测）。
     *
     * extra JSON 中标记了 drivenByProfile / scenarioId / group。
     * 由于 SQLite JSON 操作跨版本兼容性差，这里先按 scenarioPattern 过滤全部相关事件，
     * 再由调用方解析 extra 做分组统计；返回原始事件供 [ScenarioEffectStats] 计算。
     *
     * 注意：scenarioId 已内插到 scenarioPattern（如 %"scenarioId":1%），无需单独绑定，
     * 否则 Room KSP 会因"未使用绑定参数"报错。
     */
    @Query(
        "SELECT * FROM user_action_events WHERE occurredAt >= :since AND " +
            "(extra LIKE :scenarioPattern) ORDER BY occurredAt ASC"
    )
    suspend fun queryScenarioEventsSince(since: Long, scenarioPattern: String): List<UserActionEventEntity>
}
