package com.trae.social.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库 schema 迁移集合（#320：从 DataModule 拆分）。
 *
 * 原 DataModule.kt 内联了 7 条 Migration 定义，约 370 行 SQL 与 schema 变更说明，
 * 使 DI 模块文件膨胀到 560+ 行且职责混杂（既提供 Hilt 绑定，又承载 schema 演进历史）。
 *
 * 迁移本身是纯 schema 演进逻辑，与 Hilt 注入无关，独立到本文件后：
 * - DataModule 回归"纯 DI 绑定"职责，可读性提升；
 * - 迁移历史集中可追溯，新增 schema 版本时只需在此追加一条 [Migration] 并加入 [ALL_MIGRATIONS]；
 * - 后续 #282 的 MigrationTest 可直接引用 [ALL_MIGRATIONS] 数组做全量迁移回放测试。
 *
 * 迁移语义、SQL 文本与孤儿行过滤策略与原 DataModule 完全一致——本文件仅做物理拆分，
 * 不改动任何迁移行为（包括 foreign_keys pragma 默认 OFF 期间用 WHERE EXISTS 过滤孤儿的策略）。
 */

/**
 * IMPL-5：v1 → v2，interactions 表新增 (tweetId,accountId,type) 唯一索引。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_interactions_tweetId_accountId_type` " +
                "ON `interactions` (`tweetId`, `accountId`, `type`)"
        )
    }
}

/**
 * IMPL-16：v2 → v3，accounts 表新增 timezone 列（默认 Asia/Shanghai）。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `accounts` ADD COLUMN `timezone` TEXT NOT NULL DEFAULT 'Asia/Shanghai'"
        )
    }
}

/**
 * IMPL-22：v3 → v4，为 tweets/interactions/follow_relations/persona_dynamic_fields/
 * scheduler_logs/image_usages 添加外键约束（CASCADE 删除）。
 *
 * SQLite ALTER TABLE 不支持添加外键，必须重建表：
 * rename old → create new with FK → copy data（含孤儿行过滤）→ drop old → 重建索引。
 * 迁移期间 foreign_keys pragma 默认 OFF，但迁移后 Room 会开启，
 * 故 INSERT 用 WHERE EXISTS 过滤孤儿行，避免 foreign_key_check 失败。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // ---- tweets: authorId -> accounts.id CASCADE ----
        database.execSQL("ALTER TABLE `tweets` RENAME TO `_tweets_old_v3`")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `tweets` (`id` TEXT NOT NULL, `authorId` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, `mediaPath` TEXT, `mediaTheme` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `likeCount` INTEGER NOT NULL, " +
                "`commentCount` INTEGER NOT NULL, `retweetCount` INTEGER NOT NULL, " +
                "`isAiGenerated` INTEGER NOT NULL, `deduplicationKey` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`authorId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "INSERT INTO `tweets` (`id`, `authorId`, `text`, `mediaPath`, `mediaTheme`, " +
                "`createdAt`, `likeCount`, `commentCount`, `retweetCount`, `isAiGenerated`, `deduplicationKey`) " +
                "SELECT t.`id`, t.`authorId`, t.`text`, t.`mediaPath`, t.`mediaTheme`, " +
                "t.`createdAt`, t.`likeCount`, t.`commentCount`, t.`retweetCount`, " +
                "t.`isAiGenerated`, t.`deduplicationKey` FROM `_tweets_old_v3` t " +
                "WHERE EXISTS (SELECT 1 FROM `accounts` a WHERE a.`id` = t.`authorId`)"
        )
        database.execSQL("DROP TABLE `_tweets_old_v3`")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_tweets_createdAt` ON `tweets` (`createdAt`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_tweets_authorId` ON `tweets` (`authorId`)")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tweets_deduplicationKey` ON `tweets` (`deduplicationKey`)")

        // ---- interactions: tweetId -> tweets.id CASCADE, accountId -> accounts.id CASCADE ----
        database.execSQL("ALTER TABLE `interactions` RENAME TO `_interactions_old_v3`")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `interactions` (`id` TEXT NOT NULL, `tweetId` TEXT NOT NULL, " +
                "`accountId` TEXT NOT NULL, `type` TEXT NOT NULL, `content` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `scheduledAt` INTEGER NOT NULL, `executedAt` INTEGER, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`tweetId`) REFERENCES `tweets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "INSERT INTO `interactions` (`id`, `tweetId`, `accountId`, `type`, `content`, " +
                "`createdAt`, `scheduledAt`, `executedAt`) " +
                "SELECT i.`id`, i.`tweetId`, i.`accountId`, i.`type`, i.`content`, " +
                "i.`createdAt`, i.`scheduledAt`, i.`executedAt` FROM `_interactions_old_v3` i " +
                "WHERE EXISTS (SELECT 1 FROM `tweets` tw WHERE tw.`id` = i.`tweetId`) " +
                "AND EXISTS (SELECT 1 FROM `accounts` a WHERE a.`id` = i.`accountId`)"
        )
        database.execSQL("DROP TABLE `_interactions_old_v3`")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_interactions_scheduledAt` ON `interactions` (`scheduledAt`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_interactions_tweetId` ON `interactions` (`tweetId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_interactions_accountId` ON `interactions` (`accountId`)")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_interactions_tweetId_accountId_type` ON `interactions` (`tweetId`, `accountId`, `type`)")

        // ---- follow_relations: followerId -> accounts.id CASCADE, followeeId -> accounts.id CASCADE ----
        database.execSQL("ALTER TABLE `follow_relations` RENAME TO `_follow_relations_old_v3`")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `follow_relations` (`followerId` TEXT NOT NULL, " +
                "`followeeId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`followerId`, `followeeId`), " +
                "FOREIGN KEY(`followerId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`followeeId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "INSERT INTO `follow_relations` (`followerId`, `followeeId`, `createdAt`) " +
                "SELECT f.`followerId`, f.`followeeId`, f.`createdAt` FROM `_follow_relations_old_v3` f " +
                "WHERE EXISTS (SELECT 1 FROM `accounts` a1 WHERE a1.`id` = f.`followerId`) " +
                "AND EXISTS (SELECT 1 FROM `accounts` a2 WHERE a2.`id` = f.`followeeId`)"
        )
        database.execSQL("DROP TABLE `_follow_relations_old_v3`")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_follow_relations_followeeId` ON `follow_relations` (`followeeId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_follow_relations_followerId` ON `follow_relations` (`followerId`)")

        // ---- persona_dynamic_fields: accountId -> accounts.id CASCADE ----
        database.execSQL("ALTER TABLE `persona_dynamic_fields` RENAME TO `_persona_dynamic_fields_old_v3`")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `persona_dynamic_fields` (`accountId` TEXT NOT NULL, " +
                "`lifeStory` TEXT NOT NULL, `workInfo` TEXT NOT NULL, " +
                "`relationshipNetwork` TEXT NOT NULL, `mood` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`), " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "INSERT INTO `persona_dynamic_fields` (`accountId`, `lifeStory`, `workInfo`, " +
                "`relationshipNetwork`, `mood`, `updatedAt`) " +
                "SELECT p.`accountId`, p.`lifeStory`, p.`workInfo`, p.`relationshipNetwork`, " +
                "p.`mood`, p.`updatedAt` FROM `_persona_dynamic_fields_old_v3` p " +
                "WHERE EXISTS (SELECT 1 FROM `accounts` a WHERE a.`id` = p.`accountId`)"
        )
        database.execSQL("DROP TABLE `_persona_dynamic_fields_old_v3`")

        // ---- scheduler_logs: accountId -> accounts.id CASCADE ----
        database.execSQL("ALTER TABLE `scheduler_logs` RENAME TO `_scheduler_logs_old_v3`")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `scheduler_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `accountId` TEXT NOT NULL, `action` TEXT NOT NULL, " +
                "`result` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `errorMessage` TEXT, " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "INSERT INTO `scheduler_logs` (`id`, `timestamp`, `accountId`, `action`, `result`, " +
                "`durationMs`, `errorMessage`) " +
                "SELECT s.`id`, s.`timestamp`, s.`accountId`, s.`action`, s.`result`, " +
                "s.`durationMs`, s.`errorMessage` FROM `_scheduler_logs_old_v3` s " +
                "WHERE EXISTS (SELECT 1 FROM `accounts` a WHERE a.`id` = s.`accountId`)"
        )
        database.execSQL("DROP TABLE `_scheduler_logs_old_v3`")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduler_logs_timestamp` ON `scheduler_logs` (`timestamp`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduler_logs_accountId` ON `scheduler_logs` (`accountId`)")

        // ---- image_usages: accountId -> accounts.id CASCADE ----
        database.execSQL("ALTER TABLE `image_usages` RENAME TO `_image_usages_old_v3`")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `image_usages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`accountId` TEXT NOT NULL, `imageHash` TEXT NOT NULL, `usedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "INSERT INTO `image_usages` (`id`, `accountId`, `imageHash`, `usedAt`) " +
                "SELECT u.`id`, u.`accountId`, u.`imageHash`, u.`usedAt` FROM `_image_usages_old_v3` u " +
                "WHERE EXISTS (SELECT 1 FROM `accounts` a WHERE a.`id` = u.`accountId`)"
        )
        database.execSQL("DROP TABLE `_image_usages_old_v3`")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_image_usages_accountId` ON `image_usages` (`accountId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_image_usages_imageHash` ON `image_usages` (`imageHash`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_image_usages_usedAt` ON `image_usages` (`usedAt`)")
    }
}

/**
 * IMPL-38：v4 → v5，新增 account_active_hours 反向索引表，并从现有 accounts 的
 * activeWindows JSON 列回填索引行，使升级后 getActiveInHour 可直接走 SQL JOIN。
 *
 * JSON 格式为 `[true,false,true,...]`（24 槽 bool 数组），手动解析避免迁移期依赖
 * kotlinx.serialization 的运行时反射。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 创建反向索引表（FK CASCADE 删除随账号联动）
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `account_active_hours` (" +
                "`accountId` TEXT NOT NULL, `hour` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`, `hour`), " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_account_active_hours_hour` ON `account_active_hours` (`hour`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_account_active_hours_accountId` ON `account_active_hours` (`accountId`)")

        // 回填：扫描现有 accounts，解析 activeWindows JSON，为 true 的小时槽插入索引行
        database.query("SELECT `id`, `activeWindows` FROM `accounts`").use { cursor ->
            while (cursor.moveToNext()) {
                val accountId = cursor.getString(0)
                val json = if (cursor.isNull(1)) "[]" else cursor.getString(1)
                val activeHours = parseActiveWindowsJson(json)
                activeHours.forEach { hour ->
                    database.execSQL(
                        "INSERT OR IGNORE INTO `account_active_hours` (`accountId`, `hour`) VALUES (?, ?)",
                        arrayOf(accountId, hour)
                    )
                }
            }
        }
    }

    /**
     * 解析 `[true,false,true,...]` 格式的 JSON bool 数组，返回值为 true 的下标列表。
     * 容错：空/非数组/非法 token 返回空列表，不抛异常（迁移期宁可丢索引行也不崩）。
     */
    private fun parseActiveWindowsJson(json: String): List<Int> {
        if (json.isBlank()) return emptyList()
        val trimmed = json.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val inner = trimmed.substring(1, trimmed.length - 1)
        if (inner.isBlank()) return emptyList()
        val tokens = inner.split(",").map { it.trim() }
        val result = mutableListOf<Int>()
        tokens.forEachIndexed { hour, token ->
            if (hour > 23) return@forEachIndexed
            if (token == "true") result.add(hour)
        }
        return result
    }
}

/**
 * v5 → v6：新增 comments 表持久化评论列表，并从 interactions 表回填历史
 * COMMENT 类型互动（content 非空）作为既有评论，使评论弹层打开即可展示历史评论。
 *
 * 注：interactions 表 (tweetId,accountId,type) 唯一索引导致历史每用户每推文
 * 至多一条 COMMENT，回填仅迁移这些已存在的记录；后续新评论直接写入 comments 表，
 * 不再受该唯一索引约束，支持同一用户对同一推文发表多条评论。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 建表结构与 Room 根据 CommentEntity 生成的 schema 保持一致
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `comments` (" +
                "`id` TEXT NOT NULL, `tweetId` TEXT NOT NULL, " +
                "`authorId` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`tweetId`) REFERENCES `tweets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`authorId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_comments_tweetId` ON `comments` (`tweetId`)"
        )
        // B3 修复：创建 tweets 表的复合索引（与 TweetEntity @Index 注解一致），
        // 升级用户缺少此索引会导致 Room schema 校验失败崩溃
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tweets_authorId_createdAt` ON `tweets` (`authorId`, `createdAt`)"
        )
        // M8 修复：回填历史 COMMENT 互动（content 非空且已执行）作为既有评论。
        // 仅迁移 executedAt IS NOT NULL 的记录，避免把"已调度未执行"的 COMMENT 也回填为可见评论。
        // 使用 executedAt（执行时间）作为 comments.createdAt，与新写入路径时间戳语义一致。
        database.execSQL(
            "INSERT INTO `comments` (`id`, `tweetId`, `authorId`, `content`, `createdAt`) " +
                "SELECT `id`, `tweetId`, `accountId`, `content`, `executedAt` " +
                "FROM `interactions` " +
                "WHERE `type` = 'COMMENT' AND `content` IS NOT NULL AND `content` != '' " +
                "AND `executedAt` IS NOT NULL"
        )
    }
}

/**
 * #146：v6 → v7，新增用户行为建模六张表（user_action_events / user_profile_snapshots /
 * user_profile_versions / user_profile_overrides / user_profile_feedback /
 * user_profile_rollbacks）+ 索引。
 *
 * 六张表均无外键（用户行为数据独立于虚拟账号体系，不随账号级联删除），
 * 仅受"清除我的画像数据"按钮手动清空。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 1. user_action_events（原始事件流）
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_action_events` (" +
                "`id` TEXT NOT NULL, `type` TEXT NOT NULL, `screen` TEXT NOT NULL, " +
                "`targetId` TEXT, `targetKind` TEXT, `extra` TEXT, `durationMs` INTEGER, " +
                "`occurredAt` INTEGER NOT NULL, `session` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_action_events_occurredAt` ON `user_action_events` (`occurredAt`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_action_events_type_occurredAt` ON `user_action_events` (`type`, `occurredAt`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_action_events_session` ON `user_action_events` (`session`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_action_events_targetId_type` ON `user_action_events` (`targetId`, `type`)")

        // 2. user_profile_snapshots（基础分析快照）
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_profile_snapshots` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `payload` TEXT NOT NULL, " +
                "`eventWindowStart` INTEGER NOT NULL, `eventWindowEnd` INTEGER NOT NULL, " +
                "`computedAt` INTEGER NOT NULL, `source` TEXT NOT NULL)"
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_profile_snapshots_computedAt` ON `user_profile_snapshots` (`computedAt`)")

        // 3. user_profile_versions（LLM 深度画像版本，含 isActive）
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_profile_versions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `payload` TEXT NOT NULL, " +
                "`narrative` TEXT NOT NULL, `modelProvider` TEXT NOT NULL, " +
                "`promptHash` TEXT NOT NULL, `inputFingerprint` TEXT NOT NULL, " +
                "`snapshotId` INTEGER, `rollbackFrom` INTEGER, " +
                "`isActive` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL)"
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_profile_versions_snapshotId` ON `user_profile_versions` (`snapshotId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_profile_versions_createdAt` ON `user_profile_versions` (`createdAt`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_profile_versions_inputFingerprint` ON `user_profile_versions` (`inputFingerprint`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_profile_versions_isActive` ON `user_profile_versions` (`isActive`)")

        // 4. user_profile_overrides（用户显式覆盖）
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_profile_overrides` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, " +
                "`key` TEXT NOT NULL, `value` TEXT NOT NULL, `reason` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                "`superseded` INTEGER NOT NULL DEFAULT 0)"
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_profile_overrides_type_key` ON `user_profile_overrides` (`type`, `key`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_profile_overrides_superseded` ON `user_profile_overrides` (`superseded`)")

        // 5. user_profile_feedback（对话历史）
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_profile_feedback` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `role` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, `appliedActions` TEXT, `rollbackPreviews` TEXT, " +
                "`createdAt` INTEGER NOT NULL)"
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_profile_feedback_createdAt` ON `user_profile_feedback` (`createdAt`)")

        // 6. user_profile_rollbacks（回滚历史审计）
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_profile_rollbacks` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fromVersionId` INTEGER NOT NULL, " +
                "`toVersionId` INTEGER NOT NULL, `reason` TEXT NOT NULL, " +
                "`appliedAt` INTEGER NOT NULL)"
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_user_profile_rollbacks_appliedAt` ON `user_profile_rollbacks` (`appliedAt`)")
    }
}

/**
 * v7 → v8：合并 #227 与 #151 两项 schema 变更。
 *
 * #227：删除 tweets 表冗余的 authorId 单列索引。
 * 复合索引 `(authorId, createdAt)` 的最左前缀已完全覆盖 `WHERE authorId = ?` 查询，
 * 单列索引 `index_tweets_authorId` 是冗余的——每条 INSERT/UPDATE 都多维护一份 B-Tree，
 * 产生写放大。DROP 后所有按 authorId 过滤的查询仍命中复合索引：
 * - [com.trae.social.core.data.dao.TweetDao.getByAuthor]
 * - [com.trae.social.core.data.dao.TweetDao.observeByAuthor]
 * - [com.trae.social.core.data.dao.TweetDao.countByAuthorSince]
 * - [com.trae.social.core.data.dao.TweetDao.countByAuthorInWindow]
 *
 * 使用 `DROP INDEX IF EXISTS` 兼容 v7 之前由 Room 自动创建的索引名
 * `index_tweets_authorId`（Room 命名规则：`index_<table>_<col>`）。
 *
 * #151：新增 `llm_endpoints` 表承载多端点配置 + 协议格式 + 能力声明 + 全局排序。
 * API Key 不入此表（Room 不加密），仍走 EncryptedSharedPreferences，
 * 端点 id 改为 `api_key_${endpointId}` 命名空间（迁移逻辑见
 * [com.trae.social.core.data.repository.ConfigRepository.migrateLegacyProviderConfigsIfNeeded]）。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // #227：删除冗余 authorId 单列索引
        database.execSQL("DROP INDEX IF EXISTS `index_tweets_authorId`")
        // #151：新增 llm_endpoints 表
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `llm_endpoints` (" +
                "`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                "`protocol` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, `capabilities` TEXT NOT NULL, " +
                "`orderIndex` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_llm_endpoints_orderIndex` ON `llm_endpoints` (`orderIndex`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_llm_endpoints_protocol` ON `llm_endpoints` (`protocol`)"
        )
    }
}

/**
 * 全部 schema 迁移，按版本升序排列。
 *
 * 调用方（[com.trae.social.core.data.di.DataModule.provideAppDatabase]）直接
 * `.addMigrations(*ALL_MIGRATIONS)`，新增 schema 版本时只需在此数组追加一条 [Migration]。
 *
 * #282：MigrationTest 桩可直接引用本数组做全量迁移回放，避免漏测某条迁移。
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
)
