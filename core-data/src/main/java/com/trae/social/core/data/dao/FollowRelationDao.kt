package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.FollowRelationEntity
import kotlinx.coroutines.flow.Flow

/**
 * 关注关系数据访问对象。
 */
@Dao
interface FollowRelationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(relation: FollowRelationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(relations: List<FollowRelationEntity>)

    @Delete
    suspend fun delete(relation: FollowRelationEntity)

    @Query("DELETE FROM follow_relations WHERE followerId = :followerId AND followeeId = :followeeId")
    suspend fun delete(followerId: String, followeeId: String)

    @Query("SELECT * FROM follow_relations WHERE followeeId = :followeeId ORDER BY createdAt DESC")
    suspend fun getFollowers(followeeId: String): List<FollowRelationEntity>

    @Query("SELECT * FROM follow_relations WHERE followerId = :followerId ORDER BY createdAt DESC")
    suspend fun getFollowing(followerId: String): List<FollowRelationEntity>

    /**
     * #315：JOIN 一次取回 [accountId] 关注的所有账号资料，替代调用方 N 次 `getById`。
     *
     * INNER JOIN 自动丢弃 followee 账号已被删除的孤儿关系——与旧实现 `mapNotNull { getById }
     * 的过滤效果一致。按关注时间倒序排列，与 [getFollowing] 保持一致。
     */
    @Query(
        """
        SELECT a.* FROM accounts a
        INNER JOIN follow_relations f ON a.id = f.followeeId
        WHERE f.followerId = :accountId
        ORDER BY f.createdAt DESC
        """
    )
    suspend fun getFollowingWithAccounts(accountId: String): List<AccountEntity>

    /**
     * #315：JOIN 一次取回关注 [accountId] 的所有粉丝账号资料，替代调用方 N 次 `getById`。
     *
     * INNER JOIN 自动丢弃 follower 账号已被删除的孤儿关系。按关注时间倒序排列，
     * 与 [getFollowers] 保持一致。
     */
    @Query(
        """
        SELECT a.* FROM accounts a
        INNER JOIN follow_relations f ON a.id = f.followerId
        WHERE f.followeeId = :accountId
        ORDER BY f.createdAt DESC
        """
    )
    suspend fun getFollowersWithAccounts(accountId: String): List<AccountEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM follow_relations WHERE followerId = :followerId AND followeeId = :followeeId)")
    suspend fun exists(followerId: String, followeeId: String): Boolean

    @Query("SELECT COUNT(*) FROM follow_relations WHERE followeeId = :followeeId")
    suspend fun countFollowers(followeeId: String): Int

    @Query("SELECT COUNT(*) FROM follow_relations WHERE followerId = :followerId")
    suspend fun countFollowing(followerId: String): Int

    // #184：observe 版本——FollowListViewModel.toggleFollow 写库后 ProfileViewModel 自动刷新计数
    @Query("SELECT COUNT(*) FROM follow_relations WHERE followeeId = :followeeId")
    fun observeFollowersCount(followeeId: String): Flow<Int>

    // #184：observe 版本——与 observeFollowersCount 配套
    @Query("SELECT COUNT(*) FROM follow_relations WHERE followerId = :followerId")
    fun observeFollowingCount(followerId: String): Flow<Int>
}
