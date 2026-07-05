package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.PersonaDynamicFieldEntity
import kotlinx.coroutines.flow.Flow

/**
 * 账号数据访问对象。
 *
 * 同时承载 [PersonaDynamicFieldEntity] 的操作（人设动态字段与人设账号 1:1 关联）。
 */
@Dao
abstract class AccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id")
    abstract suspend fun getById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    abstract fun observeById(id: String): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE isVirtual = 1 ORDER BY createdAt ASC")
    abstract fun getVirtualAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isVirtual = 1 ORDER BY createdAt ASC")
    abstract suspend fun getVirtualAccountsList(): List<AccountEntity>

    @Query("SELECT * FROM accounts ORDER BY createdAt ASC LIMIT :size OFFSET :offset")
    abstract suspend fun getAccounts(offset: Int, size: Int): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM accounts")
    abstract suspend fun count(): Int

    /**
     * 获取在指定小时槽位活跃的虚拟账号。
     *
     * activeWindows 为 24 槽 bool 数组（JSON 存储），无法在 SQL 层高效过滤，
     * 因此先取出全部虚拟账号后在内存中过滤。账号总量约 220，开销可接受。
     */
    @Transaction
    open suspend fun getActiveInHour(hour: Int): List<AccountEntity> {
        require(hour in 0..23) { "hour must be in 0..23, but was $hour" }
        return getVirtualAccountsList().filter { account ->
            hour in account.activeWindows.indices && account.activeWindows[hour]
        }
    }

    /**
     * 获取当前时刻（基于传入 hour）活跃的虚拟账号列表（一次性快照）。
     */
    suspend fun getActiveAccountsNow(hour: Int): List<AccountEntity> = getActiveInHour(hour)

    @Upsert
    abstract suspend fun upsertDynamicFields(fields: PersonaDynamicFieldEntity)

    @Query("SELECT * FROM persona_dynamic_fields WHERE accountId = :accountId")
    abstract suspend fun getDynamicFields(accountId: String): PersonaDynamicFieldEntity?

    /**
     * 更新账号动态摘要字段（AccountEntity 表中的 denormalized 副本）。
     */
    @Query(
        """
        UPDATE accounts
        SET dynamicLifeStory = :lifeStory,
            dynamicWorkInfo = :workInfo,
            recentMood = :mood,
            updatedAt = :updatedAt
        WHERE id = :accountId
        """
    )
    abstract suspend fun updateAccountDynamicSummary(
        accountId: String,
        lifeStory: String,
        workInfo: String,
        mood: String,
        updatedAt: Long
    )
}
