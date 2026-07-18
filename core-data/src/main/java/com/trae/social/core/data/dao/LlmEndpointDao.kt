package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
