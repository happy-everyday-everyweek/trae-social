package com.trae.social.core.data.repository

import androidx.room.withTransaction
import com.trae.social.core.data.dao.AccountDao
import com.trae.social.core.data.db.AppDatabase
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.PersonaDynamicFieldEntity
import javax.inject.Inject
import javax.inject.Singleton

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

    companion object {
        const val PAGE_SIZE = 20
    }
}
