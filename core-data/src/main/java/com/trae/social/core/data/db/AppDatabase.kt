package com.trae.social.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.trae.social.core.data.dao.AccountDao
import com.trae.social.core.data.dao.FollowRelationDao
import com.trae.social.core.data.dao.ImageUsageDao
import com.trae.social.core.data.dao.InteractionDao
import com.trae.social.core.data.dao.SchedulerLogDao
import com.trae.social.core.data.dao.TweetDao
import com.trae.social.core.data.dao.UserConfigDao
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.FollowRelationEntity
import com.trae.social.core.data.entity.ImageUsageEntity
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.PersonaDynamicFieldEntity
import com.trae.social.core.data.entity.SchedulerLogEntity
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.entity.UserConfigEntity

/**
 * 应用主数据库。
 *
 * - version=4（IMPL-22：tweets/interactions/follow_relations/persona_dynamic_fields/
 *   scheduler_logs/image_usages 添加外键约束，删除账号级联清理孤儿记录）
 * - exportSchema=true（RISK-9：schema JSON 输出至 schemas/）
 * - TypeConverters 处理 JSON 字段与枚举
 * - 发布版 schema 变更须提供显式 Migration（RISK-9）
 */
@Database(
    entities = [
        AccountEntity::class,
        TweetEntity::class,
        InteractionEntity::class,
        FollowRelationEntity::class,
        PersonaDynamicFieldEntity::class,
        UserConfigEntity::class,
        SchedulerLogEntity::class,
        ImageUsageEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun tweetDao(): TweetDao
    abstract fun interactionDao(): InteractionDao
    abstract fun followRelationDao(): FollowRelationDao
    abstract fun userConfigDao(): UserConfigDao
    abstract fun schedulerLogDao(): SchedulerLogDao
    abstract fun imageUsageDao(): ImageUsageDao

    companion object {
        const val DATABASE_NAME = "social.db"
    }
}
