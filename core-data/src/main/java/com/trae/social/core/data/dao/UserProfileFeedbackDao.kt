package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trae.social.core.data.entity.UserProfileFeedbackEntity

/**
 * 用户与反馈智能体的对话历史 DAO（用户掌控层）。
 */
@Dao
interface UserProfileFeedbackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: UserProfileFeedbackEntity): Long

    @Query("SELECT * FROM user_profile_feedback ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<UserProfileFeedbackEntity>

    @Query("SELECT * FROM user_profile_feedback ORDER BY createdAt ASC")
    suspend fun all(): List<UserProfileFeedbackEntity>

    @Query("DELETE FROM user_profile_feedback")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM user_profile_feedback WHERE createdAt >= :ts")
    suspend fun countSince(ts: Long): Int
}
