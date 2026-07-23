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
 *
 * 注意：upsert / upsertAll 使用 [@Upsert][Upsert] 而非 `@Insert(REPLACE)`。
 * IMPL-22 引入 CASCADE 外键后，REPLACE 会先 DELETE 再 INSERT，级联删除 tweets /
 * interactions / image_usages 等全部子表数据。@Upsert 仅在主键冲突时 UPDATE，
 * 不触发级联删除，保证 upsert 已存在账号时不丢数据。
 */
@Dao
abstract class AccountDao {

    @Upsert
    abstract suspend fun upsertAll(accounts: List<AccountEntity>)

    @Upsert
    abstract suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id")
    abstract suspend fun getById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    abstract fun observeById(id: String): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE isVirtual = 1 ORDER BY createdAt ASC")
    abstract fun getVirtualAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isVirtual = 1 ORDER BY createdAt ASC")
    abstract suspend fun getVirtualAccountsList(): List<AccountEntity>

    /**
     * #318：取推荐关注的候选虚拟账号——单条 SQL 完成 isVirtual / 非自身 / 未关注三层过滤，
     * 替代调用方 `while(true)` 翻页 `getAccounts(page)` + 内存 filter 的全量加载模式。
     *
     * `NOT IN (SELECT followeeId ... WHERE followerId = :selfId)` 子查询排除已关注账号；
     * 子查询返回 NULL 时 NOT IN 行为为 NULL-safe（SQLite 对 NULL NOT IN (...) 的语义
     * 是"不在集合内"，与预期一致）。
     *
     * 不在 SQL 层做 LIMIT / ORDER BY RANDOM()：driven 组需在内存按兴趣向量打分取 Top N，
     * control 组才随机——两种排序策略不同，统一在内存处理以保证 driven/control 路径对称。
     * 候选集通常 ≤ 200 条虚拟账号，单次查询 + 内存打分成本远低于原 N 次分页查询。
     */
    @Query(
        """
        SELECT * FROM accounts
        WHERE isVirtual = 1
          AND id != :selfId
          AND id NOT IN (
              SELECT followeeId FROM follow_relations WHERE followerId = :selfId
          )
        ORDER BY createdAt ASC
        """
    )
    abstract suspend fun getCandidateVirtualAccounts(selfId: String): List<AccountEntity>

    /**
     * 选取最久未更新的虚拟账号（#75）。
     *
     * m1 修复：用单条 LEFT JOIN 查询替代调用方分页加载全部账号 + 逐账号 getDynamicFields
     * 的 N+1 模式（~220 账号 = 220 次单查）。未更新过的账号 persona_dynamic_fields 行缺失，
     * updatedAt 为 NULL，`p.updatedAt IS NOT NULL` 对 NULL 求值得 0，升序排最前优先更新；
     * 已更新账号按 updatedAt 升序；再以 createdAt 升序兜底保证结果稳定。
     */
    @Query(
        """
        SELECT a.* FROM accounts a
        LEFT JOIN persona_dynamic_fields p ON a.id = p.accountId
        WHERE a.isVirtual = 1
        ORDER BY p.updatedAt IS NOT NULL, p.updatedAt ASC, a.createdAt ASC
        LIMIT :count
        """
    )
    abstract suspend fun getVirtualAccountsLeastRecentlyUpdated(count: Int): List<AccountEntity>

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
