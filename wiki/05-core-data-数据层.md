# core-data 数据层

数据层模块，namespace `com.trae.social.core.data`，包路径统一为 `com.trae.social.core.data.*`（#291 修复：原 `com.trae.social.data.gallery.*` 已重命名为 `com.trae.social.core.data.gallery.*`）。compileSdk 34, minSdk 26, JVM 17。

## Room 数据库 AppDatabase

- `@Database` + `@TypeConverters(Converters)`。
- 数据库名 `social.db`，version=6（代码注解值），`exportSchema=true`（schema JSON 输出至 `schemas/`）。
- 10 张表注册顺序：`accounts`, `account_active_hours`, `tweets`, `interactions`, `follow_relations`, `persona_dynamic_fields`, `user_configs`, `scheduler_logs`, `image_usages`, `comments`。
- 8 个 abstract DAO：`accountDao` / `tweetDao` / `interactionDao` / `followRelationDao` / `userConfigDao` / `schedulerLogDao` / `imageUsageDao` / `commentDao`。
- 5 条 Migration（定义在 `DataModule.kt`）：
  1. `MIGRATION_1_2`：interactions 加唯一索引 `index_interactions_tweetId_accountId_type`。
  2. `MIGRATION_2_3`：accounts 加 `timezone` 列默认 `'Asia/Shanghai'`。
  3. `MIGRATION_3_4`：6 张表加外键 CASCADE，rename+create+copy+drop+reindex 完整重建流程，含 `WHERE EXISTS` 孤儿行过滤。
  4. `MIGRATION_4_5`：新建 `account_active_hours` 反向索引表，遍历 `accounts.activeWindows` JSON 回填，手写 token 切分解析。
  5. `MIGRATION_5_6`：新建 `comments` 表，从 interactions 回填历史 COMMENT 记录。
- `fallbackToDestructiveMigrationOnDowngrade()`（仅降级破坏性重建，升级走显式 Migration）。

## Entity 清单（10 张表）

### 1. AccountEntity（accounts）

| 项 | 内容 |
| --- | --- |
| 主键 | `id`（UUID） |
| 唯一索引 | `username` |
| 普通索引 | `avatarSeed`, `isVirtual` |
| 外键 | 无 |

- 列：`displayName`, `username`, `avatarSeed`, `bio`, `profession`, `ageRange`, `culturalBackground`, `worldview`, `values`, `languageStyle`, `catchphrase`(List<String> JSON), `emojiPreference`(List<String> JSON), `typoRate`(Double), `activeWindows`(List<Boolean> JSON 24 槽), `isVirtual`(Boolean), `createdAt`, `updatedAt`, `dynamicLifeStory`, `dynamicWorkInfo`, `recentMood`, `timezone`（默认 `Asia/Shanghai`）。

### 2. AccountActiveHourEntity（account_active_hours）

| 项 | 内容 |
| --- | --- |
| 复合主键 | `(accountId, hour)` |
| 索引 | `hour`, `accountId` |
| 外键 | `accountId` -> `accounts.id` CASCADE |

- 列：`accountId`, `hour`（0-23）。
- 用途：将 `activeWindows` 24 槽 JSON 展开为行，支撑 `WHERE hour=:hour` JOIN 查询活跃账号。

### 3. TweetEntity（tweets）

| 项 | 内容 |
| --- | --- |
| 主键 | `id` |
| 索引 | `createdAt`, `authorId` |
| 唯一索引 | `deduplicationKey` |
| 外键 | `authorId` -> `accounts.id` CASCADE |

- 列：`authorId`, `text`, `mediaPath`, `mediaTheme`, `createdAt`, `likeCount`, `commentCount`, `retweetCount`, `isAiGenerated`, `deduplicationKey`。

### 4. InteractionEntity（interactions）

| 项 | 内容 |
| --- | --- |
| 主键 | `id` |
| 索引 | `scheduledAt`, `tweetId`, `accountId` |
| 唯一索引 | `(tweetId, accountId, type)` |
| 外键 | `tweetId` -> `tweets`, `accountId` -> `accounts` CASCADE |

- 列：`type`（`InteractionType` 枚举：LIKE / COMMENT / RETWEET / FOLLOW）, `content`, `createdAt`, `scheduledAt`, `executedAt`。

### 5. FollowRelationEntity（follow_relations）

| 项 | 内容 |
| --- | --- |
| 复合主键 | `(followerId, followeeId)` |
| 索引 | `followeeId`, `followerId` |
| 外键 | 双 FK CASCADE |

- 列：`createdAt`。

### 6. PersonaDynamicFieldEntity（persona_dynamic_fields）

| 项 | 内容 |
| --- | --- |
| 主键 | `accountId`（FK CASCADE） |

- 列：`lifeStory`, `workInfo`, `relationshipNetwork`(List<String> JSON), `mood`, `updatedAt`。

### 7. UserConfigEntity（user_configs）

| 项 | 内容 |
| --- | --- |
| 主键 | `key` |

- KV 结构，列 `key`, `value`。敏感数据不走此表。

### 8. SchedulerLogEntity（scheduler_logs）

| 项 | 内容 |
| --- | --- |
| 主键 | `id`（autoGenerate Long） |
| 索引 | `timestamp`, `accountId` |
| 外键 | `accountId` CASCADE |

- 列：`action`, `result`, `durationMs`, `errorMessage`。

### 9. ImageUsageEntity（image_usages）

| 项 | 内容 |
| --- | --- |
| 主键 | `id`（autoGenerate） |
| 索引 | `accountId`, `imageHash`, `usedAt` |
| 外键 | `accountId` CASCADE |

- 列：`imageHash`, `usedAt`。

### 10. CommentEntity（comments）

| 项 | 内容 |
| --- | --- |
| 主键 | `id`（UUID） |
| 索引 | `tweetId` |
| 外键 | `tweetId` -> `tweets`, `authorId` -> `accounts` CASCADE |

- 列：`tweetId`, `authorId`, `content`, `createdAt`。
- 背景：interactions 唯一索引限制同用户同推文一条 COMMENT，故独立表承载多条评论。

### 查询结果 POJO

- `CommentWithAuthor`：JOIN 结果，作者被删时字段 null 兜底。
- `ActionCount` / `CallStatistics`：聚合查询。

## DAO 清单（8 个）

### AccountDao

- `@Upsert` 用非 REPLACE 避免 CASCADE。
- `getActiveInHour`：JOIN `account_active_hours`。
- `upsertWithActiveHours`：`@Transaction` 同步反向索引。
- `upsertDynamicFields`。
- `updateAccountDynamicSummary`。

### TweetDao

- `insert`：ABORT。
- `insertAllOrIgnore`：IGNORE，种子幂等。
- `getFeedPagingSource`：Paging3。
- `countByAuthorSince`。
- `countByAuthorInWindow`。
- `updateLikeCount`：delta。

### InteractionDao

- `insert`：IGNORE 幂等返回 rowId。
- `getPendingBefore`。
- `markExecuted`：返回受影响行数作幂等守卫。
- `executeInteractionsAndUpdateTweet`：`@Transaction` 原子计数。

### FollowRelationDao

- `insert`：IGNORE。
- `exists`。
- `countFollowers` / `countFollowing`。

### UserConfigDao

- `set`：REPLACE / `get` / `delete`。

### SchedulerLogDao

- `getRecent`。
- `countByAction`：GROUP BY。
- `getCallStatistics`：COALESCE + 互斥分类 `rate_limited` 优先。

### ImageUsageDao

- `isUsedSince`：EXISTS。
- `getUsedHashes`。

### CommentDao

- `insert`：REPLACE。
- `getCommentsForTweet`：LEFT JOIN `accounts`。

## Repository 清单（6 个）

- `AccountRepository`：`getAccounts` 分页 `PAGE_SIZE=20`；`updateDynamicFields` 双写事务保证详情/列表一致。
- `TweetRepository`：`getFeedFlow` Paging3 config `pageSize=20` / `prefetchDistance=10` / `enablePlaceholders=false`；`countByAuthorInWindow`。
- `InteractionRepository`：`scheduleInteraction` / `getPendingInteractions` / `executeInteractionsAndUpdateTweet`。
- `CommentRepository`：`addComment` / `getCommentsForTweet`。
- `ConfigRepository`：详见下节。

## ConfigRepository（配置仓库）

- `@Singleton`，注入 `@ApplicationContext` + `@SecurePreferences` SharedPreferences。
- 内部 DataStore：`Context.socialDataStore`（`preferencesDataStore "social_prefs"`）。
- `activityLevelChanges`: `SharedFlow<AiActivityLevel>`（`MutableSharedFlow extraBufferCapacity=1`）。
  - `setAiActivityLevel` 写 DataStore 后 `tryEmit(level)`。
  - `SchedulerInitializer` 收集后 REPLACE 重排 Worker。
- 敏感数据（`EncryptedSharedPreferences`，key 命名 `api_key_${provider.id}` / `base_url_${provider.id}` / `model_${provider.id}`）：
  - `getApiKey` / `setApiKey`
  - `getBaseUrl` / `setBaseUrl`
  - `getModelName` / `setModelName`
  - `apiKeyPreview`（脱敏：<=8 返回 `***`，否则 `首4***末4`，`withContext IO`）
- 非敏感配置（DataStore）：
  - `getDefaultProvider` / `setDefaultProvider`（key `default_provider`）
  - `isOnboardingCompleted` / `setOnboardingCompleted`（key `onboarding_completed`）
  - `isOnboardingSkipped` / `setOnboardingSkipped`（key `onboarding_skipped`）
  - `getAiActivityLevel`（默认 `MEDIUM`）/ `setAiActivityLevel`（key `ai_activity_level`）

## TypeConverters（Converters.kt）

- Json 配置 `ignoreUnknownKeys=true`, `encodeDefaults=true`。
- 4 组转换：
  1. `List<String>` <-> `String`（JSON 数组，null -> `"[]"`）
  2. `List<Boolean>` <-> `String`（同上，用于 `activeWindows`）
  3. `InteractionType` <-> `String?`（枚举 name，反序列化 `valueOf` + `runCatching` 容错返回 null）
- 序列化器 `ListSerializer(String.serializer())` / `ListSerializer(Boolean.serializer())`。

## EncryptedSharedPreferences

- 限定符 `@SecurePreferences`（`@Qualifier` BINARY）。
- 文件名 `social_secure_prefs`。
- `MasterKey` 基于 Android Keystore `AES256_GCM`。
- 加密方案 `AES256_SIV`(key) + `AES256_GCM`(value)。
- 三级降级容错：
  1. 创建失败 -> `deleteSharedPreferences` 删除损坏文件重建
  2. 仍失败回退普通 `SharedPreferences`（文件名 `social_secure_prefs_fallback`, `MODE_PRIVATE`）
  - 每级 `runCatching.getOrElse` + `Timber.e`。
- `@Singleton`，由 `ConfigRepository` 通过 `@SecurePreferences` 注入。

## PersonaSeeder

- `@Singleton`，注入 `@ApplicationContext` + `AppDatabase` + `AccountDao` + `TweetDao`。
- 资产加载：`assets/personas/` 目录，`personas_` 前缀 `.json` 后缀 `sorted()`。
- Json 配置 `ignoreUnknownKeys` / `isLenient` / `coerceInputValues=true`。
- 幂等性：
  - `isSeeding AtomicBoolean` 防并发。
  - 判定 `accountDao.count()>=EXPECTED_TOTAL(220) && tweetDao.count()>0` 时跳过（崩溃中断后 tweets 缺失会触发补导）。
  - accounts 用 `@Upsert`（不触发 CASCADE），tweets 用 `insertAllOrIgnore`（冲突跳过）。
- 种子内容：
  - 220 个虚拟账号（10 分片 × 20+）。
  - 历史推文 `daysAgo` 1-30，随机 `likeCount`(0-80) / `commentCount`(0-15) / `retweetCount`(0-25)，`deduplicationKey="seed_${id}_$index"`。
  - `ensureUserSelfAccount` 插入 `id="user-self"`、`isVirtual=false` 账号。
- 进度反馈：`seedIfNeeded(): Flow<SeedProgress>`（cold flow，`flowOn IO`），每文件 emit 一次，最终 `isComplete=true`；`imported` 仅统计虚拟账号避免超 100%。
- 事务性：每文件 accounts+tweets 在 `database.withTransaction` 内；accounts 用 `upsertAllWithActiveHours` 同步反向索引。

## 图库系统（gallery 包）

### AssetProvider（接口）

- `listAssets(path)` / `openAsset(path)`。
- 抽象因 assets 在 app 模块。

### GalleryImageTheme（枚举）

- `LANDSCAPE` / `FOOD` / `CITY` / `PET` / `SPORT` / `ART` / `TECH` / `NATURE` / `NONE`。
- `fromString` 大小写不敏感。
- `themeToString` NONE 返回空串。

### ImageUsagePort（接口）

- `recentlyUsedAssets(accountId, sinceMillis)` / `recordUsage(accountId, assetPath, usedAtMillis)`。

### LocalImageGallery（接口）

- `pickRandom(theme, accountId): String?` 返回 asset 路径如 `"gallery/landscape/3.svg"`。

### LocalImageGalleryImpl

- `@Singleton`。
- 流程：
  1. `ensureIndexLoaded()` 读 `gallery/index.json`（`Mutex` 保证一次，`@Volatile indexCache`）
  2. 查 `recentlyUsedAssets(now-30天)` 排除已用
  3. `candidateThemes` 指定主题置顶其余回退
  4. 逐主题过滤 available 后 `random` 选取
  5. `recordUsage` 记录（失败仅 warn）
  6. `withContext Default`，index 加载 IO

### RoomImageUsagePort

- `@Singleton`，`imageHash` 字段直接用 `assetPath` 作唯一标识。
- 30 天去重窗口进程重启仍有效。

### GalleryModule

- `@Binds` 两个实现 + `@Provides @GalleryJson Json`。
- `GalleryJson` 限定符避免 Json 绑定冲突。

## 枚举（config/LlmConfig.kt）

### LlmProvider(id, displayName)

| 枚举值 | id | displayName |
| --- | --- | --- |
| `OPENAI` | `"openai"` | `"OpenAI"` |
| `ANTHROPIC` | `"anthropic"` | `"Anthropic"` |
| `GEMINI` | `"gemini"` | `"Google Gemini"` |
| `CUSTOM` | `"custom"` | `"自定义端点"` |

- companion `fromId`。
- 注意：无显式 `custom` 布尔字段。

### AiActivityLevel(id, rpmLimit, dailyPostsPerAccount, personaUpdateBatchSize, personaUpdatePeriodDays)

| 枚举值 | id | rpmLimit | dailyPostsPerAccount | personaUpdateBatchSize | personaUpdatePeriodDays |
| --- | --- | --- | --- | --- | --- |
| `LOW` | - | 10 | 2 | 10 | 14 |
| `MEDIUM` | - | 30 | 4 | 20 | 7 |
| `HIGH` | - | 60 | 8 | 40 | 3 |

- companion `fromId`。

## build.gradle.kts 要点

- plugins：`android.library`, `kotlin.android`, `kotlin.serialization`, `hilt`, `ksp`。namespace `com.trae.social.core.data`。
- Room schema 导出：

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

- 依赖：Room(runtime/ktx/paging/compiler)、Retrofit+OkHttp、DataStore+security.crypto、Paging、serialization、coroutines、hilt、timber、测试(room.testing/mockk/mockwebserver)。
- `consumer-rules.pro`：keep entity/dao/db 包 + `@Serializable` 类与 `$$serializer`。

## 关键设计要点

### @Upsert vs @Insert(REPLACE)

引入 CASCADE 外键后 REPLACE 会先 DELETE 触发级联删除子表，故 upsert 改用 `@Upsert`。

### 双写一致性

`updateDynamicFields` 单事务内同时写 `PersonaDynamicFieldEntity` 与 `AccountEntity` denormalized 摘要。

### 原子计数

`executeInteractionsAndUpdateTweet` 用 `markExecuted` 返回值作幂等守卫，避免 Worker 重试导致 `likeCount` 翻倍。

### 反向索引表 account_active_hours

核心性能优化，将全表+JSON 反序列化过滤优化为 SQL JOIN 按小时槽过滤。
