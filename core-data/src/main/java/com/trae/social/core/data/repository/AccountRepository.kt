package com.trae.social.core.data.repository

import androidx.room.withTransaction
import com.trae.social.core.data.dao.AccountDao
import com.trae.social.core.data.db.AppDatabase
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.PersonaDynamicFieldEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 账号仓库：封装账号查询与人设动态字段更新。
 */
@Singleton
class AccountRepository @Inject constructor(
    private val database: AppDatabase,
    private val accountDao: AccountDao
) {

    suspend fun getAccounts(page: Int): List<AccountEntity> {
        val offset = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        return accountDao.getAccounts(offset, PAGE_SIZE)
    }

    suspend fun getById(id: String): AccountEntity? = accountDao.getById(id)

    // #184：observe 版本——TimelineViewModel.selfProfile 改用 Flow 订阅，
    // PersonaUpdateWorker 更新人设后头部自动刷新
    fun observeById(id: String): Flow<AccountEntity?> = accountDao.observeById(id)

    /**
     * 获取当前时刻活跃的虚拟账号列表。
     * @param hour 当前小时（0-23）
     */
    suspend fun getActiveAccountsNow(hour: Int): List<AccountEntity> =
        accountDao.getActiveInHour(hour)

    /**
     * 更新人设动态字段：同时写入 [PersonaDynamicFieldEntity] 与 [AccountEntity] 的动态摘要副本。
     *
     * IMPL-25：双写在同一事务内，崩溃后人设详情页与列表页显示一致。
     */
    suspend fun updateDynamicFields(
        accountId: String,
        lifeStory: String,
        workInfo: String,
        relationshipNetwork: List<String>,
        mood: String,
        updatedAt: Long
    ) {
        database.withTransaction {
            accountDao.upsertDynamicFields(
                PersonaDynamicFieldEntity(
                    accountId = accountId,
                    lifeStory = lifeStory,
                    workInfo = workInfo,
                    relationshipNetwork = relationshipNetwork,
                    mood = mood,
                    updatedAt = updatedAt
                )
            )
            accountDao.updateAccountDynamicSummary(
                accountId = accountId,
                lifeStory = lifeStory,
                workInfo = workInfo,
                mood = mood,
                updatedAt = updatedAt
            )
        }
    }

    suspend fun getDynamicFields(accountId: String): PersonaDynamicFieldEntity? =
        accountDao.getDynamicFields(accountId)

    /**
     * B1 修复：透传 DAO 层的 getVirtualAccountsList，供 AppColdStartFiller 等调用方使用。
     */
    suspend fun getVirtualAccountsList(): List<AccountEntity> =
        accountDao.getVirtualAccountsList()

    /**
     * #318：取推荐关注的候选虚拟账号（isVirtual=1 / 非自身 / 未关注），单条 SQL 完成
     * 三层过滤，替代调用方 while(true) 翻页 + 内存 filter 的全量加载模式。
     */
    suspend fun getCandidateVirtualAccounts(selfId: String): List<AccountEntity> =
        accountDao.getCandidateVirtualAccounts(selfId)

    /**
     * m1 修复：选取最久未更新的虚拟账号（#75），单条 JOIN 查询替代 N+1。
     */
    suspend fun getVirtualAccountsLeastRecentlyUpdated(count: Int): List<AccountEntity> =
        accountDao.getVirtualAccountsLeastRecentlyUpdated(count)

    companion object {
        const val PAGE_SIZE = 20
    }
}
