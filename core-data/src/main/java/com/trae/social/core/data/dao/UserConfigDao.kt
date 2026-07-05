package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trae.social.core.data.entity.UserConfigEntity

/**
 * 用户配置数据访问对象（KV 结构）。
 *
 * 注意：敏感数据（API Key）不存此表，走 EncryptedSharedPreferences。
 */
@Dao
interface UserConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(config: UserConfigEntity)

    @Query("SELECT value FROM user_configs WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM user_configs")
    suspend fun getAll(): List<UserConfigEntity>

    @Query("DELETE FROM user_configs WHERE key = :key")
    suspend fun delete(key: String)
}
