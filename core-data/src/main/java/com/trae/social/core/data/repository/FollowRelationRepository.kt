package com.trae.social.core.data.repository

import com.trae.social.core.data.dao.FollowRelationDao
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.FollowRelationEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 关注关系仓库（#314：从 feature-profile ViewModel 下沉 DAO 访问）。
 *
 * 原 [ProfileViewModel] / [FollowListViewModel] 直接注入 [FollowRelationDao]，
 * 违反依赖倒置原则——表现层 ViewModel 依赖数据层具体 DAO 实现，使 DAO 签名变更
 * 直接波及 ViewModel，且无法在 ViewModel 单测中用假 Repository 替换 DB 行为。
 *
 * 本仓库作为领域边界封装以下能力：
 * - 关注/取关写入（[insert] / [unfollow]）
 * - 关注/粉丝关系读取（[getFollowing] / [getFollowers]）
 * - JOIN 账号资料读取（[getFollowingWithAccounts] / [getFollowersWithAccounts]，#315）
 * - 计数（[countFollowing] / [countFollowers] / [observeFollowingCount] / [observeFollowersCount]）
 *
 * 仓库方法为纯透传，无业务逻辑——其价值在于解耦与可测性，而非封装 SQL 细节。
 * 后续若需在关注/取关时附加副作用（如通知、缓存失效），可在此集中扩展而无需改 ViewModel。
 */
@Singleton
class FollowRelationRepository @Inject constructor(
    private val followRelationDao: FollowRelationDao,
) {

    /**
     * 关注：写入关注关系。
     *
     * @return 新插入行 rowId；若因 (followerId, followeeId) 主键冲突走 IGNORE 未实际插入则返回 -1L。
     * 调用方据此判断是否为新关注（用于埋点去重，避免 A/B 场景 6 delta 高估）。
     */
    suspend fun insert(relation: FollowRelationEntity): Long = followRelationDao.insert(relation)

    /** 取关：按 (followerId, followeeId) 主键删除关注关系。 */
    suspend fun unfollow(followerId: String, followeeId: String) =
        followRelationDao.delete(followerId, followeeId)

    /** 取某账号关注的所有人关系列表（仅关系行，不含账号资料）。 */
    suspend fun getFollowing(followerId: String): List<FollowRelationEntity> =
        followRelationDao.getFollowing(followerId)

    /** 取关注某账号的所有粉丝关系列表（仅关系行，不含账号资料）。 */
    suspend fun getFollowers(followeeId: String): List<FollowRelationEntity> =
        followRelationDao.getFollowers(followeeId)

    /**
     * #315：JOIN 一次取回 [accountId] 关注的所有账号资料，替代调用方 N 次 `getById`。
     * INNER JOIN 自动丢弃 followee 账号已删除的孤儿关系。
     */
    suspend fun getFollowingWithAccounts(accountId: String): List<AccountEntity> =
        followRelationDao.getFollowingWithAccounts(accountId)

    /**
     * #315：JOIN 一次取回关注 [accountId] 的所有粉丝账号资料，替代调用方 N 次 `getById`。
     * INNER JOIN 自动丢弃 follower 账号已删除的孤儿关系。
     */
    suspend fun getFollowersWithAccounts(accountId: String): List<AccountEntity> =
        followRelationDao.getFollowersWithAccounts(accountId)

    /** 某账号是否已关注另一账号。 */
    suspend fun exists(followerId: String, followeeId: String): Boolean =
        followRelationDao.exists(followerId, followeeId)

    /** 某账号的关注数（一次性快照）。 */
    suspend fun countFollowing(followerId: String): Int = followRelationDao.countFollowing(followerId)

    /** 某账号的粉丝数（一次性快照）。 */
    suspend fun countFollowers(followeeId: String): Int = followRelationDao.countFollowers(followeeId)

    /**
     * #184：observe 版本——关注数 Flow，写库后自动刷新，供 ProfileViewModel 持续订阅。
     */
    fun observeFollowingCount(followerId: String): Flow<Int> =
        followRelationDao.observeFollowingCount(followerId)

    /**
     * #184：observe 版本——粉丝数 Flow，与 [observeFollowingCount] 配套。
     */
    fun observeFollowersCount(followeeId: String): Flow<Int> =
        followRelationDao.observeFollowersCount(followeeId)
}
