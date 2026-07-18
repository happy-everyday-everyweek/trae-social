package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.trae.social.core.data.entity.UserProfileOverrideEntity

/**
 * 用户显式覆盖 DAO（用户掌控层）。
 *
 * 同 key 新覆盖产生时，旧覆盖 superseded=true（软删除保留审计），
 * [active] 仅返回 superseded=false 的生效覆盖。
 */
@Dao
interface UserProfileOverrideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(override: UserProfileOverrideEntity): Long

    @Query("SELECT * FROM user_profile_overrides WHERE superseded = 0 ORDER BY createdAt DESC")
    suspend fun active(): List<UserProfileOverrideEntity>

    @Query("SELECT * FROM user_profile_overrides ORDER BY createdAt DESC")
    suspend fun all(): List<UserProfileOverrideEntity>

    @Query("SELECT * FROM user_profile_overrides WHERE type = :type AND superseded = 0 ORDER BY createdAt DESC")
    suspend fun byType(type: String): List<UserProfileOverrideEntity>

    /** 同 key 旧覆盖软删除（superseded=true），保留审计。 */
    @Query("UPDATE user_profile_overrides SET superseded = 1 WHERE type = :type AND `key` = :key AND superseded = 0")
    suspend fun markSuperseded(type: String, key: String)

    /**
     * M-反馈2 修复：原子地软删同 key 旧覆盖并插入新覆盖。
     *
     * 原先 [markSuperseded] + [insert] 分两步非事务，并发下可能出现旧覆盖已被软删、
     * 新覆盖却插入失败（或与另一并发插入产生冲突）的脏状态。用 [@Transaction][Transaction]
     * 包裹两步，保证要么同时成功要么同时回滚。
     */
    @Transaction
    suspend fun markSupersededAndInsert(type: String, key: String, override: UserProfileOverrideEntity) {
        markSuperseded(type, key)
        insert(override)
    }

    @Query("DELETE FROM user_profile_overrides")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM user_profile_overrides WHERE superseded = 0")
    suspend fun activeCount(): Int
}
