package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 调度日志实体（RISK-15：可观测性）。
 *
 * 记录每次调度事件的时间、账号、动作、结果、耗时与错误信息，
 * 供开发者选项查看。
 * IMPL-22：accountId 外键关联 accounts.id，删除账号时级联删除其日志（SET NULL 更合适，
 * 但 accountId 非 null，用 CASCADE 保证不留孤儿记录）。
 */
@Entity(
    tableName = "scheduler_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["accountId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        )
    ]
)
data class SchedulerLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val accountId: String,
    val action: String,
    val result: String,
    val durationMs: Long,
    val errorMessage: String?
)
