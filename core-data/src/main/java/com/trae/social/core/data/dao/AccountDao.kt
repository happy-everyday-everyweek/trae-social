package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import com.trae.social.core.data.entity.AccountActiveHourEntity
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
     * IMPL-38：原实现 `getVirtualAccountsList().filter { ... }` 全表加载 220 条账号并
     * 反序列化每条 activeWindows JSON 后内存过滤。现改为通过 [account_active_hours]
     * 反向索引表 JOIN，仅取出该小时活跃的账号行，避免全表扫描与重复 JSON 解析。
     *
     * @param hour 小时槽 0-23
     */
    @Query(
        """
        SELECT a.* FROM accounts a
        INNER JOIN account_active_hours h ON a.id = h.accountId
        WHERE a.isVirtual = 1 AND h.hour = :hour
        ORDER BY a.createdAt ASC
        """
    )
    abstract suspend fun getActiveInHour(hour: Int): List<AccountEntity>

    /**
     * 获取当前时刻（基于传入 hour）活跃的虚拟账号列表（一次性快照）。
     */
    suspend fun getActiveAccountsNow(hour: Int): List<AccountEntity> = getActiveInHour(hour)

    /**
     * 单账号 upsert 并同步活跃小时索引（IMPL-38）。
     *
     * 在同一事务内：upsert 账号 → 删除该账号旧索引行 → 按 activeWindows 插入新索引行。
     */
    @Transaction
    open suspend fun upsertWithActiveHours(account: AccountEntity) {
        upsert(account)
        syncActiveHours(account.id, account.activeWindows)
    }

    /**
     * 批量 upsert 并同步活跃小时索引（IMPL-38）。
     *
     * 在同一事务内逐账号 upsert + 重建索引，保证一致性。
     */
    @Transaction
    open suspend fun upsertAllWithActiveHours(accounts: List<AccountEntity>) {
        accounts.forEach { account ->
            upsert(account)
            syncActiveHours(account.id, account.activeWindows)
        }
    }

    /**
     * 删除该账号的全部活跃小时索引行，再按 [activeWindows] 中为 true 的槽位重新插入。
     */
    private suspend fun syncActiveHours(accountId: String, activeWindows: List<Boolean>) {
        deleteActiveHours(accountId)
        val rows = activeWindows.mapIndexedNotNull { hour, active ->
            if (active && hour in 0..23) AccountActiveHourEntity(accountId, hour) else null
        }
        if (rows.isNotEmpty()) insertActiveHours(rows)
    }

    @Query("DELETE FROM account_active_hours WHERE accountId = :accountId")
    abstract suspend fun deleteActiveHours(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertActiveHours(hours: List<AccountActiveHourEntity>)

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
