package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.trae.social.core.data.entity.LlmEndpointEntity
import kotlinx.coroutines.flow.Flow

/**
 * LLM 端点配置 DAO（#151）。
 *
 * 仅持久化端点元数据；API Key 走 EncryptedSharedPreferences。
 */
@Dao
interface LlmEndpointDao {

    @Query("SELECT * FROM llm_endpoints ORDER BY orderIndex ASC")
    suspend fun listAll(): List<LlmEndpointEntity>

    @Query("SELECT * FROM llm_endpoints ORDER BY orderIndex ASC")
    fun observeAll(): Flow<List<LlmEndpointEntity>>

    @Query("SELECT * FROM llm_endpoints WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LlmEndpointEntity?

    @Query("SELECT COUNT(*) FROM llm_endpoints")
    suspend fun count(): Int

    @Query("SELECT MAX(orderIndex) FROM llm_endpoints")
    suspend fun maxOrderIndex(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(endpoint: LlmEndpointEntity)

    @Update
    suspend fun update(endpoint: LlmEndpointEntity)

    @Delete
    suspend fun delete(endpoint: LlmEndpointEntity)

    @Query("DELETE FROM llm_endpoints WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 事务重写排序：先把所有端点 orderIndex 置为带 offset 的临时值，
     * 再按 [orderedIds] 顺序重新赋值 0..n-1，避免唯一/重排冲突。
     */
    @Query("UPDATE llm_endpoints SET orderIndex = orderIndex + :offset")
    suspend fun shiftAllOrderIndex(offset: Int)

    @Query("UPDATE llm_endpoints SET orderIndex = :newOrder WHERE id = :id")
    suspend fun setOrderIndex(id: String, newOrder: Int)

    /**
     * 原子重排端点顺序：在单个 Room 事务内完成 [shiftAllOrderIndex] + 多次 [setOrderIndex]。
     *
     * 原先 [ConfigRepository.reorderEndpoints] 在 Repository 层非事务地串调两步，
     * 中途失败（进程被杀 / 异常）会导致 orderIndex 处于不一致的中间状态
     * （部分端点已 shift、部分已重排）。@Transaction 保证要么全部提交要么全部回滚。
     */
    @Transaction
    suspend fun reorder(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        // 用 offset 避免重排过程中 unique 冲突（虽然 orderIndex 非 unique，但保险起见）
        val shift = orderedIds.size + 10
        shiftAllOrderIndex(shift)
        orderedIds.forEachIndexed { idx, id ->
            setOrderIndex(id, idx)
        }
    }
}
