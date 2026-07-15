package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.trae.social.core.data.entity.UserProfileSnapshotEntity
import com.trae.social.core.data.entity.UserProfileVersionEntity

/**
 * 用户画像快照与 LLM 版本 DAO。
 *
 * 版本不可变：所有 LLM 版本永久保留（受 maxProfileVersions 上限保护），
 * 回滚 = 激活旧版本（[setActive]），不删除新版本。
 */
@Dao
interface UserProfileDao {

    // ---- 快照 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: UserProfileSnapshotEntity): Long

    @Query("SELECT * FROM user_profile_snapshots ORDER BY computedAt DESC LIMIT 1")
    suspend fun latestSnapshot(): UserProfileSnapshotEntity?

    @Query("SELECT * FROM user_profile_snapshots WHERE id = :id")
    suspend fun snapshotById(id: Long): UserProfileSnapshotEntity?

    @Query("SELECT COUNT(*) FROM user_profile_snapshots WHERE computedAt >= :ts")
    suspend fun snapshotCountSince(ts: Long): Int

    // ---- 版本 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVersion(version: UserProfileVersionEntity): Long

    @Query("SELECT * FROM user_profile_versions ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestVersion(): UserProfileVersionEntity?

    @Query("SELECT * FROM user_profile_versions WHERE isActive = 1 LIMIT 1")
    suspend fun activeVersion(): UserProfileVersionEntity?

    @Query("SELECT * FROM user_profile_versions WHERE id = :id")
    suspend fun versionById(id: Long): UserProfileVersionEntity?

    @Query("SELECT * FROM user_profile_versions ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentVersions(limit: Int): List<UserProfileVersionEntity>

    @Query("SELECT COUNT(*) FROM user_profile_versions")
    suspend fun versionCount(): Int

    /** 回滚定位：该时间点之前最近的版本。 */
    @Query("SELECT * FROM user_profile_versions WHERE createdAt <= :ts ORDER BY createdAt DESC LIMIT :limit")
    suspend fun versionsBeforeTime(ts: Long, limit: Int): List<UserProfileVersionEntity>

    /** 回滚定位：narrative 含关键词的最近版本。 */
    @Query("SELECT * FROM user_profile_versions WHERE narrative LIKE :keywordPattern ORDER BY createdAt DESC LIMIT :limit")
    suspend fun versionsByNarrativeKeyword(keywordPattern: String, limit: Int): List<UserProfileVersionEntity>

    /** 删除最旧的非激活版本（超 maxProfileVersions 上限时调用）。 */
    @Query(
        "DELETE FROM user_profile_versions WHERE id IN " +
            "(SELECT id FROM user_profile_versions WHERE isActive = 0 ORDER BY createdAt ASC LIMIT :count)"
    )
    suspend fun deleteOldestInactive(count: Int): Int

    /**
     * 事务内：设目标版本 active=true，其余全部 active=false。
     */
    @Transaction
    suspend fun setActive(versionId: Long) {
        clearAllActive()
        markActive(versionId)
    }

    @Query("UPDATE user_profile_versions SET isActive = 0")
    suspend fun clearAllActive()

    @Query("UPDATE user_profile_versions SET isActive = 1 WHERE id = :versionId")
    suspend fun markActive(versionId: Long)

    // ---- 清除（"清除我的画像数据"按钮）----

    @Query("DELETE FROM user_profile_snapshots")
    suspend fun deleteAllSnapshots()

    @Query("DELETE FROM user_profile_versions")
    suspend fun deleteAllVersions()

    @Query("DELETE FROM user_action_events")
    suspend fun deleteAllEvents()
}
