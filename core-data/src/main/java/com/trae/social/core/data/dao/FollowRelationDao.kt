package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
