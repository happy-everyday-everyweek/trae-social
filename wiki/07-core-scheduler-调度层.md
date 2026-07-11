# core-scheduler 调度层

AI 调度层，namespace `com.trae.social.core.scheduler`。基于 WorkManager，包含 4 类 Worker + 规则解析 + 限流 + 配额 + 前台服务。依赖 `core-data` 与 `core-llm`。`AiActivityLevel` 枚举定义在 `core-data` 模块。

## 模块结构

```
core-scheduler/src/main/java/com/trae/social/core/scheduler/
├── BootReceiver.kt
├── SchedulerForegroundService.kt
├── SchedulerInitializer.kt
├── di/SchedulerModule.kt
├── ratelimit/{DailyQuotaChecker.kt, SchedulerRateLimiter.kt}
├── rule/{DeduplicationKeys.kt, MissedWindow.kt, ScheduleRule.kt, ScheduleRuleResolver.kt, TimeWindow.kt}
└── work/{InteractionWorker.kt, PendingInteractionWorker.kt, PersonaUpdateWorker.kt, SchedulerInitializerWorker.kt, TweetGenerationWorker.kt, WorkerKeys.kt}
```

## SchedulerInitializer（初始化入口）

`object`，入口 `initialize(app: Context)`。实际由 `MainActivity.onCreate` 调用（注释明确：不能放 `Application.onCreate`，Android 12+ 后台启动前台服务崩溃；`SchedulerInitializer` 内部有 `AtomicBoolean` 幂等守卫）。`BootReceiver` 经 `SchedulerInitializerWorker` 间接触发（开机后 30 秒）。

用 `@EntryPoint` `@InstallIn(SingletonComponent)` 接入依赖（因 `object` 非 Hilt 创建）：`SchedulerEntryPoint` 含 `accountRepository` / `tweetRepository` / `configRepository` / `schedulerLogDao` / `schedulerRateLimiter`。

`initialize()` 五步：
1. `startForegroundService(app)`（O+ `startForegroundService`，捕获 `IllegalStateException` / `SecurityException`）；
2. 调度恢复（错过的活跃窗补发）；
3. 入队下一批 `TweetGenerationWorker`；
4. 入队周期任务 `PendingInteractionWorker`（15min）；
5. 入队周期任务 `PersonaUpdateWorker`（按档位）。

### 调度恢复

- `level = getAiActivityLevel`（失败回退 `MEDIUM`）；
- `lastRun = determineLastRunTime`（查 `SchedulerLogDao.getRecent(50)` 最近 `tweet_generation` 日志，无记录回退 `now - 24h`）；
- `loadVirtualAccounts`（翻页最多 `MAX_PAGES = 12`，过滤 `isVirtual`）；
- 对每个账号用账号自身时区 `ZoneId.of(account.timezone)`（失败回退 `systemDefault`，IMPL-16 跨时区修正）；
- `ScheduleRule(accountId, activeWindows, postsPerWindow = POSTS_PER_WINDOW = 2)`；
- `missedWindows` 每窗补 1 条（`sequenceNo = 0`），用 `missedWindow.date` 计算 `windowStartMillis`（IMPL-4 跨日 key 修正），`enqueueUniqueWork(key, KEEP)`。

### 入队下一批

- `nextTrigger = ScheduleRuleResolver.nextTriggerTime(rule, now, zone, postsInWindowProvider = {accountId, ws, we -> tweetRepository.countByAuthorInWindow})`（P1 修复：窗满跳下一窗）；
- 非空则 `setInitialDelay(triggerAt - now)` `enqueueUniqueWork(KEEP)`。

### 档位变更监听（IMPL-48）

`observeActivityLevelChanges` 收集 `configRepository.activityLevelChanges`，收到新 level 后 `ExistingPeriodicWorkPolicy.REPLACE` 重排 `PersonaUpdateWorker` + `rateLimiter.reconfigure(level)`（P2 同步限流器容量）。

### 关键常量

- `LAST_RUN_LOOKUP_LIMIT = 50`
- `MAX_PAGES = 12`
- `POSTS_PER_WINDOW = 2`

## SchedulerForegroundService

继承 `Service`。`foregroundServiceType = specialUse`（IMPL-17：Android 14+ 避免 `dataSync` 6 小时配额限制）。通知渠道 `"scheduler"` `IMPORTANCE_LOW`，常驻通知"Trae Social 运行中 / 伙伴们正在活跃中，保持连接以接收最新动态"。

- `onCreate`：`createNotificationChannel` + `ServiceCompat.startForeground(this, NOTIFICATION_ID = 1001, buildNotification(), getServiceType())`（P0 修复：三参数重载兼容 `minSdk = 26`）。
- `onStartCommand`：`schedulePendingInteractions()` 入队 `PendingInteractionWorker` 15min `KEEP`，返回 `START_STICKY`。
- `getServiceType()`：API 34+ 返回 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`，否则 `0`。

### 通知常量

- `NOTIFICATION_ID = 1001`
- `CHANNEL_ID = "scheduler"`
- `CHANNEL_NAME = "社交动态"`
- 小图标 `android.R.drawable.stat_notify_sync`
- `setOngoing(true)`
- `PRIORITY_LOW`
- `CATEGORY_SERVICE`

## BootReceiver（开机自启）

监听 `ACTION_BOOT_COMPLETED` + `ACTION_LOCKED_BOOT_COMPLETED`（Direct Boot 模式）。

IMPL-18：不用 `Handler.postDelayed`（进程可能被杀），改用 `OneTimeWorkRequestBuilder<SchedulerInitializerWorker>().setInitialDelay(STARTUP_DELAY_SECONDS = 30, SECONDS).addTag(WorkerTags.BOOT_INIT)`，`enqueueUniqueWork(BOOT_INIT, KEEP)`。延迟 30 秒避免开机资源争抢，进程被杀 WorkManager 仍能重放。

`SchedulerInitializerWorker`：`@HiltWorker` `CoroutineWorker`，`doWork` 调 `SchedulerInitializer.initialize(applicationContext)`，成功 `success` 异常 `retry`。

## Worker 清单

| Worker | 类型 | 周期/触发 | 批量 | 说明 |
| --- | --- | --- | --- | --- |
| `TweetGenerationWorker` | OneTime 延迟 | 按 `nextTriggerTime` 计算的活跃窗随机时刻，成功后自链入队下一个 | 每窗 `POSTS_PER_WINDOW = 2` | 推文生成 |
| `InteractionWorker` | OneTime | 推文写入后立即入队 | 3-8 评论者/推文 | 互动排程（写 `InteractionEntity` 带 `scheduledAt`） |
| `PendingInteractionWorker` | Periodic | 15 分钟（最小允许值） | 全部 pending | 扫描并执行到期互动 |
| `PersonaUpdateWorker` | Periodic | LOW=14天 / MEDIUM=7天 / HIGH=3天 | LOW=10 / MEDIUM=20 / HIGH=40 账号 | 人设动态字段更新 |
| `SchedulerInitializerWorker` | OneTime 延迟 | 开机后 30 秒 | - | 触发 `SchedulerInitializer` |

## TweetGenerationWorker 详解

`@HiltWorker` `CoroutineWorker` `@AssistedInject`，注入 `AccountRepository` / `TweetRepository` / `ConfigRepository` / `LlmProviderRegistry` / `LocalImageGallery` / `SchedulerRateLimiter` / `DailyQuotaChecker` / `SchedulerLogDao`。持 `TweetPromptBuilder` / `TweetPostProcessor` / `ContentFilter`。

### 输入数据

- `KEY_ACCOUNT_ID`（必填）
- `KEY_DEDUP_KEY`（必填）
- `KEY_WINDOW_START`

缺失必填 -> `Result.failure`（`KEY_ERROR = "missing required inputs"`）。

### doWork 步骤（11+ 步）

1. `rateLimiter.reconfigure(level)`；
2. 查账号取时区，不存在 -> `"skipped_no_account"` `success`；
3. `isQuotaExhausted(accountId, level, accountZone)` -> `"skipped_quota"` `success`；
4. `rateLimiter.acquire()`；
5. 查最近 3 条（`RECENT_TWEETS_FOR_DEDUP = 3`）去重上下文；
6. 构 `PersonaInput` + `TweetPromptBuilder.build(persona, timeSlotDescription, recentTweets)`，`timeSlotDescription` 含"工作日/周末 HH:00-HH:00"用账号时区；
7. `chatSync(ChatConfig temperature = 0.85f, maxTokens = 320, jsonMode = true)`，捕获 `RateLimitedException` -> `"skipped_429"` `success`（不重试），`HttpException` `code == 429` 同样跳过，空响应 -> `"skipped_empty_response"` `retry`；
8. `parseTweetResult` 失败降级纯文本（`take(280)` 无图 `NONE`）；
9. `ContentFilter.containsSensitiveContent` -> `"skipped_sensitive"` `success`；
10. `TweetPostProcessor`：`Random(windowStart + accountId.hashCode())` 种子，`applyTypos(typoRate)` -> `appendEmojis(emojiPreference)` -> `truncate(280)`；
11. 配图 `gallery.pickRandom(themeStr, accountId)`；
12. 写 `TweetEntity(isAiGenerated = true, deduplicationKey)`，`SQLiteConstraintException` -> `"skipped_duplicate"` `success`（幂等）；
13. `enqueueUniqueWork("interaction_${tweet.id}", KEEP)` 使 AI 推文获互动；
14. `scheduleNextTweetGeneration` 自链入队下一个；
15. 写 `SchedulerLogEntity` `action = "tweet_generation"`，`success`（`KEY_RESULT` / `KEY_TWEET_ID`）。

### 异常处理

- `runAttemptCount >= MAX_RUN_ATTEMPTS(3)` -> `failure`，否则 `retry`。
- 退避 `EXPONENTIAL` 初始 10s，按 `10s -> 20s -> 40s -> 80s` 增长，WorkManager 内部上限 5h。

### 常量

- `MAX_TWEET_LENGTH = 280`
- `RECENT_TWEETS_FOR_DEDUP = 3`
- `MAX_RUN_ATTEMPTS = 3`
- `POSTS_PER_WINDOW = 2`

## InteractionWorker 详解

输入 `KEY_TWEET_ID`（必填）。`doWork`：

1. 查推文/作者，不存在 `success`；
2. `selectCommenters(authorId, profession, bio)`：翻页加载虚拟账号（最多 12 页）排除作者，bio + profession 关键词重叠度 + 职业匹配评分，Top（`score >= threshold / 2`）后随机，候选不足回退全量随机，目标 `min(MAX_COMMENTERS = 8, max(MIN_COMMENTERS = 3, pool.size))`，无候选 -> `"skipped_no_commenters"` `success`；
3. 分配互动类型概率 `LIKE 50% / COMMENT 30% / RETWEET 15% / FOLLOW 5%`；
4. 延迟（IMPL-20 对数正态分布 Box-Muller）：LIKE 30s-5min，COMMENT 2min-15min，RETWEET 5min-30min，FOLLOW 1min-10min，`logValue = mean + std * gaussian`，`exp` 后 `clamp`；
5. 评论批量化：COMMENT 类型者 `generateComments` 一次 LLM 调用（`ChatConfig temperature = 0.9f, maxTokens = 512, jsonMode = true`），`rateLimiter.acquire()`，`parseCommentResults` 解析，文本截断 100 字；
6. 写 `InteractionEntity(scheduledAt = now + delay, executedAt = null)`；
7. IMPL-21 短延迟（`<= 5min`）的 LIKE / FOLLOW 用 `OneTimeWorkRequest` + `setInitialDelay` 直接调度（绕过 15min 周期，"秒赞"体验）；
8. 写日志 `action = "interaction_schedule"`。

`RateLimitedException` -> `"rate_limited"` `success`。

## PendingInteractionWorker 详解

周期 15 分钟。`doWork`：

1. `getPendingInteractions(now)`，空 -> `success("no_pending")`；
2. 按 `tweetId` 分组；
3. COMMENT && content 空 者 `generateCommentsFor` 批量生成；
4. 筛选可执行（COMMENT 必须有内容）；
5. IMPL-6 `executeInteractionsAndUpdateTweet` 原子标记 `executedAt` + 累加推文计数（同 `@Transaction`）；
6. 写日志 `action = "pending_interaction"` `status = "processed_${processed}_failed_${failed}"`。

`RateLimitedException` -> `"rate_limited"` `success`。

## PersonaUpdateWorker 详解

`doWork`：

1. `level = getAiActivityLevel`（回退 `MEDIUM`），`batchSize = level.personaUpdateBatchSize`；
2. `pickRandomAccounts(batchSize)` 翻页 shuffled 取 `batchSize`；
3. 每账号 `rateLimiter.acquire()` + `updateSinglePersona`：加载动态字段，收集最近 5 条推文文本作活动事件，`PersonaUpdatePromptBuilder.build` + `chatSync(ChatConfig temperature = 0.7f, maxTokens = 512, jsonMode = true)`，`parsePersonaUpdate` 失败 -> `SKIPPED`，`shouldRollback(currentLifeStory, newLifeStory)` 或 `shouldRollback(currentWorkInfo, newWorkInfo)` 任一突变 -> `ROLLED_BACK`，通过则 `updateDynamicFields`；
4. `RateLimitedException` -> 整批跳过 `"rate_limited"` `success`；
5. 通用异常 `runAttemptCount >= 3` `failure` 否则 `retry`；
6. 写日志 `action = "persona_update"` `status = "updated_${updated}_rolledBack_${rolledBack}_failed_$failed"`。

### 常量

- `MAX_RUN_ATTEMPTS = 3`
- `RECENT_EVENTS_LIMIT = 5`
- `MAX_PAGES = 12`

## ScheduleRuleResolver（规则解析）

`object`。

### parseWindows

```kotlin
fun parseWindows(activeWindows: List<Boolean>): List<TimeWindow>
```

24 槽 bool，长度不足补 `false` 超出截断，连续 `true` 合并为 `TimeWindow(startHour, endHour)`，末尾段 `endHour = 24`。跨夜（如 22-23-0-1）不合并按自然日切分。

### nextTriggerTime

```kotlin
suspend fun nextTriggerTime(
    rule: ScheduleRule,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    random: Random = Random.Default,
    postsInWindowProvider: (suspend (accountId: String, windowStartMillis: Long, windowEndMillis: Long) -> Int?)? = null,
): Instant?
```

1. `now` 在当前活跃窗内且窗内已发布数 `< postsPerWindow` -> 窗内随机时刻（不早于 `now`）；
2. 否则今日后续活跃窗内随机时刻；
3. 今日无后续窗 -> 次日首个活跃窗；
4. 无活跃窗 -> `null`。

P1 修复 `postsInWindowProvider` 窗满跳下一窗。

### missedWindows

```kotlin
fun missedWindows(rule: ScheduleRule, lastRun: Instant?, now: Instant, zone: ZoneId): List<MissedWindow>
```

识别 `[lastRun, now]` 间完整错过的活跃窗，`lastRun` null 时按昨日 00:00 起算，今日当前小时所在窗未结束不视为错过，返回升序 `MissedWindow(date, window)` 列表（IMPL-4 补 `date` 字段）。

## SchedulerRateLimiter

包装 `core-llm` 的 `RateLimiter`，按 `AiActivityLevel` 配置容量。`@Singleton` 由 `SchedulerModule` 提供初始 `MEDIUM`。

IMPL-26：HTTP 层 `RateLimitInterceptor` 已移除，本类是唯一限流闸门，在调度入口 `acquire` 避免无效请求消耗网络栈。

### 构造

```kotlin
RateLimiter(maxTokens = initialLevel.rpmLimit, refillIntervalMillis = 60_000)
```

令牌桶容量 = `rpmLimit`（LOW=10 / MEDIUM=30 / HIGH=60），每 60 秒补充至容量。

### reconfigure

```kotlin
suspend fun reconfigure(newLevel: AiActivityLevel)
```

`Mutex.withLock` 保护，调 `rateLimiter.reconfigure(newLevel.rpmLimit)` 按比例折算保留令牌，不替换实例（IMPL-30）。

### 方法

- `acquire()`：阻塞挂起；
- `tryAcquire()`：非阻塞；
- `currentLevel()`；
- `availableTokens()`。

## DailyQuotaChecker

RISK-1 配额超限防护。查 `TweetRepository.countByAuthorSince(accountId, startOfDayMillis)` 与 `level.dailyPostsPerAccount` 比较。

IMPL-16 接受可选 `zone` 参数，调用方传账号自身时区避免跨时区配额边界漂移。

### 方法

- `isQuotaExhausted(accountId, level, now, zone)`：`count >= dailyPostsPerAccount` 返回 `true`；
- `usedToday(accountId, now, zone)`；
- `startOfDayMillis(now, zone)`：`localDate.atStartOfDay(zone).toInstant().toEpochMilli()`。

### 每日配额

- LOW = 2
- MEDIUM = 4
- HIGH = 8

条/日。

## AiActivityLevel 档位

| 档位 | id | rpmLimit | dailyPostsPerAccount | personaUpdateBatchSize | personaUpdatePeriodDays |
| --- | --- | --- | --- | --- | --- |
| LOW | low | 10 | 2 | 10 | 14 |
| MEDIUM | medium | 30 | 4 | 20 | 7 |
| HIGH | high | 60 | 8 | 40 | 3 |

档位变更触发 `REPLACE` 重排：`ConfigRepository.activityLevelChanges` `SharedFlow(extraBufferCapacity = 1)`，`setAiActivityLevel` 写 DataStore 后 `tryEmit`。`SchedulerInitializer` collect 后 `REPLACE` 重排 `PersonaUpdateWorker` + `reconfigure` 限流器。

## 辅助类与键定义

### WorkerKeys

- `KEY_ACCOUNT_ID` / `KEY_TWEET_ID` / `KEY_DEDUP_KEY` / `KEY_WINDOW_START` / `KEY_SEQUENCE_NO` / `KEY_RESULT` / `KEY_DURATION_MS` / `KEY_ERROR`
- `HTTP_TOO_MANY_REQUESTS = 429`

### WorkerTags

- `TWEET_GENERATION` / `INTERACTION` / `PENDING_INTERACTION` / `PERSONA_UPDATE` / `BOOT_INIT`

### WorkerPolicies

- `networkConstraints(NetworkType.CONNECTED)`
- `backoffPolicy = EXPONENTIAL` `BACKOFF_INITIAL_SECONDS = 10`（指数退避 10s -> 20s -> 40s -> 80s，WorkManager 内部上限 5h）
- `tweetGenerationRequest(...)` / `interactionRequest(tweetId)` / `pendingInteractionPeriodicRequest()`（15min）/ `personaUpdatePeriodicRequest(level)`（按 `personaUpdatePeriodDays` 天）

### 规则数据类

- `ScheduleRule(accountId, activeWindows, postsPerWindow, HOURS_PER_DAY = 24)`
- `TimeWindow(startHour 0-23, endHour 1-24)`
- `MissedWindow(date: LocalDate, window)`
- `DeduplicationKeys.forTweet(accountId, windowStart, sequenceNo) -> "accountId_windowStart_sequenceNo"`

### RateLimitedException

继承 `IOException`（避免 OkHttp `AsyncCall` 重抛闪退）。

### SchedulerModule

- `provideSchedulerRateLimiter`（`@Singleton` 初始 `MEDIUM`）
- `provideDailyQuotaChecker`（`@Singleton`）

Worker 通过 `@HiltWorker` + `@AssistedInject` 自动绑定。

## build.gradle.kts 与 Manifest

### plugins

- `android.library`
- `kotlin.android`
- `hilt`
- `ksp`

namespace `com.trae.social.core.scheduler`。`compileSdk 34` `minSdk 26` JVM 17。

### 依赖

- `core-data`
- `core-llm`
- `androidx.core.ktx`
- `work-runtime.ktx`
- `hilt.work` + `ksp(hilt.work.compiler)`
- `hilt.android` + `ksp(hilt.compiler)`
- `retrofit`（捕获 `HttpException` 判 429）
- `coroutines`
- `timber`
- 测试（`work.testing` / `mockk`）

### Manifest

- `core-scheduler/AndroidManifest.xml`：仅声明 `BootReceiver`（注册 `BOOT_COMPLETED`，权限在 app 模块声明避免重复）。
- `app/AndroidManifest.xml` 权限与组件（权威）：见 [17-权限与前台服务](./17-权限与前台服务.md)。

## 关键设计要点

- 三层防中断保障（RISK-3）：前台服务 + 调度恢复 + BootReceiver。
- 幂等性：`deduplicationKey` + `enqueueUniqueWork(KEEP)` + `TweetEntity.deduplicationKey` 唯一约束。
- 429 统一跳过：`RateLimitedException` -> `success`，不重试不耗配额。
- 限流单一闸门：`SchedulerRateLimiter` 是唯一限流点。
- 跨时区修正：账号自身时区用于活跃窗/配额/prompt 时段。
- 自链调度：`TweetGenerationWorker` 成功后自链入队下一个，`SchedulerInitializer` 仅入队首条。
- 档位热切换：`activityLevelChanges` SharedFlow -> `REPLACE` + `reconfigure`。
- 人设漂移防护：`shouldRollback` 相似度校验。
- 秒赞体验：短延迟 LIKE/FOLLOW 绕过 15min 周期。
- specialUse 前台服务：Android 14+ 避免 `dataSync` 6 小时配额。

> Note：`SchedulerInitializer.initialize` 由 `MainActivity.onCreate` 调用（非 `SocialApp.onCreate`，避免后台上下文启动前台服务崩溃）。
