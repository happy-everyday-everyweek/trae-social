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

    /**
     * 第七轮 review M4 修复：latestSnapshot 排除 COLD_START_SEEDING 源。
     *
     * 原查询未按 source 过滤，若 onboarding 写入了 COLD_START_SEEDING 快照且尚无
     * INCREMENTAL/FULL_RECOMPUTE 快照，则 latestSnapshot 会返回 COLD_START_SEEDING 快照，
     * 导致 CachedProfileLoader.snapshot 非空 → interestVector() 走 `snapshot != null` 分支，
     * coldStartSeeding() 代码路径成为死代码（永远不被读取）。
     *
     * 修正语义：latestSnapshot 仅返回基础分析快照（INCREMENTAL/FULL_RECOMPUTE），
     * COLD_START_SEEDING 快照仅通过 [earliestColdStartSnapshot] 读取。
     */
    @Query("SELECT * FROM user_profile_snapshots WHERE source != 'COLD_START_SEEDING' ORDER BY computedAt DESC LIMIT 1")
    suspend fun latestSnapshot(): UserProfileSnapshotEntity?

    /**
     * 第六轮 review B3 修复：查询最早的 COLD_START_SEEDING 快照。
     *
     * 冷启动 seeding 由 onboarding 兴趣选择写入（source=COLD_START_SEEDING），
     * 应取最早一条（onboarding 时写入的初始兴趣），而非最新快照。
     * 原实现 [latestSnapshot] 读最新快照且未按 source 过滤，导致：
     * 1. 冷启动 seeding 永远读到 INCREMENTAL/FULL_RECOMPUTE 快照（语义错误）
     * 2. snapshot == null（真正冷启动）时 coldStartSeeding 也为 null → 死路
     */
    @Query("SELECT * FROM user_profile_snapshots WHERE source = 'COLD_START_SEEDING' ORDER BY computedAt ASC LIMIT 1")
    suspend fun earliestColdStartSnapshot(): UserProfileSnapshotEntity?

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

    /**
     * 第二轮 review Major 3 修复:查询比指定 createdAt 严格更早的最近版本。
     *
     * 用于 `ProfileVersionStore.locate` 的"回到上个版本"语义——
     * 旧实现 `recentVersions(2).firstOrNull { it.id != active.id }` 在回滚态下
     * (active 是旧版本,如 V2 active 而 V5 最新)会返回 V5 而非 V2 的上一个版本,
     * 违反"回到上个版本"语义。改为按 active.createdAt 严格向前查询。
     */
    @Query("SELECT * FROM user_profile_versions WHERE createdAt < :ts ORDER BY createdAt DESC LIMIT :limit")
    suspend fun versionsStrictlyBeforeTime(ts: Long, limit: Int): List<UserProfileVersionEntity>

    /**
     * 回滚定位：narrative 含关键词的最近版本。
     *
     * 第五轮 review N3 修复:添加 `ESCAPE '\'` 子句,配合调用侧对 `%` / `_` / `\` 的转义,
     * 避免用户消息中含 LIKE 通配符时被 SQLite 当作模式匹配符,导致回滚定位命中非预期版本。
     */
    @Query("SELECT * FROM user_profile_versions WHERE narrative LIKE :keywordPattern ESCAPE '\\' ORDER BY createdAt DESC LIMIT :limit")
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
