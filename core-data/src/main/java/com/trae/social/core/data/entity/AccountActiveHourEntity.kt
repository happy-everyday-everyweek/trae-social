package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 账号活跃小时反向索引（IMPL-38）。
 *
 * `AccountEntity.activeWindows` 以 JSON 数组存储，无法在 SQL 层按小时槽高效过滤，
 * 原实现每次调度周期全表加载 220 条账号再内存过滤。本表将 24 槽展开为 (accountId, hour)
 * 行，支持 `WHERE hour = :hour` 直接 JOIN 出活跃账号，避免全表扫描与 JSON 反序列化。
 *
 * - 主键 (accountId, hour) 防止重复
 * - 外键 accountId -> accounts.id ON DELETE CASCADE（账号删除时自动清理索引）
 * - hour 索引支撑按小时查询
 *
 * 与 `AccountEntity.activeWindows` 保持同步：账号 upsert 时由 DAO 事务内重建该账号的索引行。
 */
@Entity(
    tableName = "account_active_hours",
    primaryKeys = ["accountId", "hour"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("hour"), Index("accountId")],
)
data class AccountActiveHourEntity(
    val accountId: String,
    /** 小时槽 0-23，true 表示该小时活跃 */
    val hour: Int,
)
