package com.trae.social.core.data.repository

import com.trae.social.core.data.dao.AccountDao
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.PersonaDynamicFieldEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号仓库：封装账号查询与人设动态字段更新。
 */
@Singleton
class AccountRepository @Inject constructor(
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
     */
    suspend fun updateDynamicFields(
        accountId: String,
        lifeStory: String,
        workInfo: String,
        relationshipNetwork: List<String>,
        mood: String,
        updatedAt: Long
    ) {
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

    suspend fun getDynamicFields(accountId: String): PersonaDynamicFieldEntity? =
        accountDao.getDynamicFields(accountId)

    companion object {
        const val PAGE_SIZE = 20
    }
}
