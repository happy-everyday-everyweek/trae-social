package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trae.social.core.data.entity.UserProfileRollbackEntity

/**
 * 画像版本回滚历史 DAO（审计）。
 */
@Dao
interface UserProfileRollbackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rollback: UserProfileRollbackEntity): Long

    @Query("SELECT * FROM user_profile_rollbacks ORDER BY appliedAt DESC")
    suspend fun all(): List<UserProfileRollbackEntity>

    @Query("DELETE FROM user_profile_rollbacks")
    suspend fun deleteAll()
}
