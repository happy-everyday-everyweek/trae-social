package com.trae.social.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.trae.social.core.data.dao.AccountDao
import com.trae.social.core.data.dao.CommentDao
import com.trae.social.core.data.dao.FollowRelationDao
import com.trae.social.core.data.dao.ImageUsageDao
import com.trae.social.core.data.dao.InteractionDao
import com.trae.social.core.data.dao.LlmEndpointDao
import com.trae.social.core.data.dao.SchedulerLogDao
import com.trae.social.core.data.dao.TweetDao
import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.dao.UserConfigDao
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.dao.UserProfileFeedbackDao
import com.trae.social.core.data.dao.UserProfileOverrideDao
import com.trae.social.core.data.dao.UserProfileRollbackDao
import com.trae.social.core.data.entity.AccountActiveHourEntity
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.CommentEntity
import com.trae.social.core.data.entity.FollowRelationEntity
import com.trae.social.core.data.entity.ImageUsageEntity
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.LlmEndpointEntity
import com.trae.social.core.data.entity.PersonaDynamicFieldEntity
import com.trae.social.core.data.entity.SchedulerLogEntity
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.entity.UserActionEventEntity
import com.trae.social.core.data.entity.UserConfigEntity
import com.trae.social.core.data.entity.UserProfileFeedbackEntity
import com.trae.social.core.data.entity.UserProfileOverrideEntity
import com.trae.social.core.data.entity.UserProfileRollbackEntity
import com.trae.social.core.data.entity.UserProfileSnapshotEntity
import com.trae.social.core.data.entity.UserProfileVersionEntity

/**
 * 应用主数据库。
 *
 * - version=4（IMPL-22：tweets/interactions/follow_relations/persona_dynamic_fields/
 *   scheduler_logs/image_usages 添加外键约束，删除账号级联清理孤儿记录）
 * - version=5（IMPL-38：新增 account_active_hours 反向索引表，支撑按小时 SQL 层过滤活跃账号）
 * - version=6（新增 comments 表持久化评论列表，评论弹层打开时从 DB 加载展示）
 * - version=7（#146：新增用户行为建模六张表：user_action_events / user_profile_snapshots /
 *   user_profile_versions / user_profile_overrides / user_profile_feedback / user_profile_rollbacks）
 * - version=8（#227：删除 tweets 表冗余 authorId 单列索引，已被复合索引最左前缀覆盖；
 *   #151：新增 llm_endpoints 表，承载多端点配置 + 协议格式 + 能力声明 + 全局排序）
 * - #87 / review 修复：exportSchema 恢复为 true。此前曾临时关闭以消除误导性配置
 *   （schemas/ 目录仅有 1.json~5.json，v6/v7/v8 缺失），但关闭 exportSchema 会让未来
 *   entity 与 migration 的 schema drift 无法在 CI 被 MigrationTestHelper 捕获。
 *   缺失的 v6/v7/v8 schema JSON 需在有 Android SDK 的环境中执行
 *   `./gradlew :core-data:assembleDebug` 重新生成，详见 #295。
 * - TypeConverters 处理 JSON 字段与枚举
 */
@Database(
    entities = [
        AccountEntity::class,
        AccountActiveHourEntity::class,
        TweetEntity::class,
        InteractionEntity::class,
        FollowRelationEntity::class,
        PersonaDynamicFieldEntity::class,
        UserConfigEntity::class,
        SchedulerLogEntity::class,
        ImageUsageEntity::class,
        CommentEntity::class,
        UserActionEventEntity::class,
        UserProfileSnapshotEntity::class,
        UserProfileVersionEntity::class,
        UserProfileOverrideEntity::class,
        UserProfileFeedbackEntity::class,
        UserProfileRollbackEntity::class,
        LlmEndpointEntity::class
    ],
    version = 8,
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
    abstract fun commentDao(): CommentDao
    abstract fun userActionDao(): UserActionDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun userProfileOverrideDao(): UserProfileOverrideDao
    abstract fun userProfileFeedbackDao(): UserProfileFeedbackDao
    abstract fun userProfileRollbackDao(): UserProfileRollbackDao
    abstract fun llmEndpointDao(): LlmEndpointDao

    companion object {
        const val DATABASE_NAME = "social.db"
    }
}
