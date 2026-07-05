package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trae.social.core.data.entity.ImageUsageEntity

/**
 * 配图使用记录数据访问对象（配图去重用）。
 */
@Dao
interface ImageUsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usage: ImageUsageEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM image_usages WHERE accountId = :accountId AND imageHash = :imageHash AND usedAt >= :since)")
    suspend fun isUsedSince(accountId: String, imageHash: String, since: Long): Boolean

    @Query("SELECT imageHash FROM image_usages WHERE accountId = :accountId AND usedAt >= :since")
    suspend fun getUsedHashes(accountId: String, since: Long): List<String>

    @Query("DELETE FROM image_usages WHERE usedAt < :before")
    suspend fun deleteBefore(before: Long): Int
}
