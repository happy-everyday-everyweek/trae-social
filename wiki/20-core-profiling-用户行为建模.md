# core-profiling 用户行为建模

用户行为建模模块，namespace `com.trae.social.core.profiling`。负责采集真实用户在应用内的行为事件、计算基础画像快照、驱动 LLM 生成深度画像版本、以反哺权重影响 Feed/评论/推荐等业务场景，并提供单轮 LLM 智能体接受用户反馈调校画像。

依赖 `core-data`（Entity / DAO / Repository / domain model）与 `core-llm`（LlmClient 与 PromptBuilder）。LLM 深度画像的 Worker 调度与生命周期由 `core-scheduler` 承担，本模块不直接持有 WorkManager。

## 5 层架构总览

```text
+--------------------------------------------------------------------------------------+
| 第 5 层  用户反馈智能体层（FeedbackAgent）                                            |
|   单轮 LLM 智能体：ProfileChatViewModel -> FeedbackAgent.handle()                     |
|   限流 10/小时，in-flight 占位带时间戳；RollbackProfileVersion 预览确认后才应用          |
+------------------------------------+-------------------------------------------------+
                                     | 9 种 FeedbackAction（白名单 + 值域 sanitize）
                                     v
+--------------------------------------------------------------------------------------+
| 第 4 层  画像反哺层（FeedbackController / ProfileAdjuster / UserProfileReadAccess）    |
|   8 个反哺场景编号（5 完整闭环 1/3/4/6/7，3 部分/预留 2/5/8）驱动 effectiveWeight 计算  |
|   override 软删除（superseded）+ 审计链；ProfileVersionStore 版本激活/回滚              |
|   ProfileCache TTL 30s；灰度 sessionId 哈希稳定分组                                    |
+------------------------------------+-------------------------------------------------+
                                     | 读取基础画像 + override
                                     v
+--------------------------------------------------------------------------------------+
| 第 3 层  LLM 深度画像层（UserProfileWorker + UserProfilePromptBuilder）                |
|   周期触发（LOW=96h / MEDIUM=48h / HIGH=24h）；指纹缓存短路；narrative 突变回滚           |
|   输出 UserProfileVersion（含 narrative + LLM 兴趣向量），单 active 版本                 |
+------------------------------------+-------------------------------------------------+
                                     | 消费基础快照
                                     v
+--------------------------------------------------------------------------------------+
| 第 2 层  基础分析层（BasicProfileAnalyzer / BasicProfileTrigger / EventTextPreParser / |
|             UserProfileAggregator）                                                   |
|   纯函数无副作用；指数时间衰减（半衰期 7 天）；增量合并；IQR 异常剔除                     |
|   双阈值触发（COUNT=100 / TIME=1h）；LLM 预解析文本信号写回事件 extra                    |
+------------------------------------+-------------------------------------------------+
                                     | 拉取事件窗口
                                     v
+--------------------------------------------------------------------------------------+
| 第 1 层  捕获层（UserActionTracker / SessionManager）                                 |
|   trackNow fire-and-forget / track 挂起直写；Channel UNLIMITED + 批写 500ms/50 条         |
|   dedup 1s/500 keys；SessionManager 30s 内 onResume 复用 sessionId                      |
+--------------------------------------------------------------------------------------+
```

## 各层职责与关键类

### 第 1 层 捕获层

| 关键类 | 职责 |
| --- | --- |
| `UserActionTracker`（接口） | 事件采集入口，`trackNow` fire-and-forget（UI 主线程直接调用，不阻塞）/ `track` 挂起直写 |
| `UserActionTrackerImpl` | Channel UNLIMITED 缓冲 + 协程批写。常量 `BATCH_WINDOW_MS=500L`、`BATCH_MAX=50`、`DEDUP_WINDOW_MS=1000L`、`DEDUP_MAX=500` |
| `UserActionSink`（接口） | DAO 写入抽象，便于单测注入 fake；生产实现 `DaoUserActionSink` 包 `UserActionDao.insertAll` |
| `ProfilingGate`（接口） | `isEnabled()` / `isDebug()`；生产实现 `ConfigProfilingGate` 读 `ConfigRepository.isProfilingEnabled`，`@Volatile enabledCache` + 5s 周期后台刷新（第二轮 review Major 5：避免主线程 `runBlocking` 读 DataStore 触发 ANR） |
| `SessionManager` | `sessionGapMs=30_000L`，30 秒内 `onResume` 复用同一 `sessionId`；`onResume`/`onPause`/`endSession`，并自动 track `SESSION_START`/`SESSION_END` |

`UserActionTrackerImpl` 批写策略：到达 `BATCH_WINDOW_MS` 或积压 `BATCH_MAX` 条触发一次 `sink.insertAll`；去重 key 为 4 元组 `type|targetId|session|occurredAt`（`occurredAt` 为毫秒时间戳），同一 key 在 `DEDUP_WINDOW_MS`（1s）内重复事件被丢弃，避免 Compose 重组与快速连点造成噪声。注意：因 `occurredAt` 参与去重，仅同毫秒事件才被去重，跨毫秒的连点仍会各自入库。

### 第 2 层 基础分析层

| 关键类 | 职责 |
| --- | --- |
| `BasicProfileAnalyzer`（object） | 纯函数无副作用。计算活跃时段、兴趣向量、互动倾向、浏览深度、发帖节奏、社交风格、周期性、置信度、证据链 |
| `BasicProfileTrigger` | 双阈值触发：`COUNT_THRESHOLD=100`、`TIME_THRESHOLD_MS=1h`、`DEBOUNCE_MS=30s`；分析窗 `ANALYSIS_WINDOW_MS=14天`；`FULL_RECOMPUTE_INTERVAL_MS=6h`；区分 `SOURCE_INCREMENTAL` / `SOURCE_FULL_RECOMPUTE` |
| `EventTextPreParser` | LLM 预解析 `PUBLISH_TWEET`/`TWEET_COMMENT` 文本，提取 `textTopic`/`textTopics`/`textSentiment`/`textIntent`；`MAX_BATCH_SIZE=20`、`LLM_TIMEOUT_MS=30_000L`；通过 `UserActionDao.updateExtra` 写回 extra |
| `UserProfileAggregator` | 分层聚合：主题层 / 时间层 / 行为层 / 反哺效果层 / 用户反馈层；`computeFeedbackEffect()` 计算 8 个场景 A/B delta；`FEEDBACK_LIMIT=10` |

`BasicProfileAnalyzer` 关键常量与权重表：

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `HALF_LIFE_DAYS` | `7.0` | 指数衰减半衰期 |
| `TEXT_TOPIC_BOOST` | `1.5` | LLM 预解析出的文本主题加成 |
| `TEXT_TOPICS_WEIGHT` | `0.5` | 文本主题权重 |

`actionWeights`（事件权重，单一表供 `computeInterestVector` 与 `baseWeight` 共用）：

| UserActionType | 权重 |
| --- | --- |
| `TWEET_VIEW` | 1.0 |
| `TWEET_LIKE` | 3.0 |
| `TWEET_COMMENT` | 5.0 |
| `TWEET_RETWEET` | 4.0 |
| `TWEET_BOOKMARK` | 6.0 |
| `TWEET_DWELL` | 2.0 |
| `PUBLISH_TWEET` | 6.0 |
| `FOLLOW` | 3.0 |
| `SESSION_START` / `SESSION_END` | 1.0 |
| `SCREEN_ENTER` / `SCREEN_LEAVE` | 1.0 |
| 其他（含 `TWEET_UNLIKE` / `TWEET_UNBOOKMARK` / `UNFOLLOW` / `IMAGE_FULLSCREEN` / `CAPTURE_PHOTO` / `APPLY_FILTER` / `PUBLISH_MODE_SWITCH` / `TAB_SWITCH` / `OPEN_*` / `ONBOARDING_*` / `FEEDBACK_*`） | `FALLBACK_WEIGHT=0.5` |

核心方法：

- `analyze()` 计算活跃时段 / 兴趣向量 / 互动倾向 / 浏览深度 / 发帖节奏 / 社交风格 / 周期性 / 置信度 / 证据链
- `decay()` 指数衰减（按事件时间到现在的天数，权重 × `0.5^(days/HALF_LIFE_DAYS)`）
- `mergeIncremental()` 增量合并（旧快照与窗口增量按衰减后权重加权融合）
- `filterIqr()` IQR 异常剔除（剔除偏离 1.5×IQR 的极端停留时长等噪声）

### 第 3 层 LLM 深度画像层

本层由 `core-scheduler` 的 `UserProfileWorker`（周期触发）驱动，调用 `core-llm` 的 `UserProfilePromptBuilder` 生成深度画像。

| 关键类（跨模块） | 模块 | 职责 |
| --- | --- | --- |
| `UserProfileWorker` | core-scheduler | 周期 LOW=96h / MEDIUM=48h / HIGH=24h（定义在 `WorkerKeys.kt` 的 `WorkerPolicies.userProfilePeriodicRequest`，非本 Worker 内）；指纹缓存短路 + MIN_NEW_EVENTS=20 短路；429 跳过；narrative 突变（`jaccardSimilarity < 0.4`，阈值定义在 `UserProfilePromptBuilder.NARRATIVE_ROLLBACK_THRESHOLD`）保留旧版本 |
| `UserProfilePromptBuilder` | core-llm | 构造 system / user prompt；`NARRATIVE_ROLLBACK_THRESHOLD=0.4`；约束 narrative 100-300 字、低置信度保守权重 |
| `ProfileVersionStore` | core-profiling | `insertAndActivate` 原子事务（避免双 active）；`applyRollback` 激活旧版本不删除新版本；`locate` 三种定位方式（versionId / aroundTimestamp / narrativeKeyword） |

详见 [07-core-scheduler 调度层](./07-core-scheduler-调度层.md) 与 [12 LLM 集成与 Prompt 工程](./12-LLM-集成与-Prompt-工程.md)。

### 第 4 层 画像反哺层

| 关键类 | 职责 |
| --- | --- |
| `FeedbackController` | `effectiveWeights()` 为薄包装：采集关返回 ZERO，否则直接委托 `UserProfileReadAccessImpl.feedbackWeights()`（真正 clamp/置信度降权/灰度 在 `UserProfileReadAccessImpl` 内完成）；`shouldApply(scenarioId)` 非挂起版仅做场景开关 + 权重>0 判定（不做灰度分流），`shouldApply(scenarioId, sessionId)` 挂起版叠加 sessionId 哈希稳定分组灰度；`selectDriven()` 场景级配额 + 主题多样性 |
| `ProfileAdjuster` | `applyAll()` 应用 `FeedbackAction`；`markSupersededAndInsert` 原子事务（旧 override 软删除 + 新 override 插入）；`resetAll()` 物理删除全部 override；`SOURCE_FEEDBACK_AGENT` |
| `UserProfileReadAccess`（接口 + `UserProfileReadAccessImpl`） | 业务侧统一读入口：`clampWeights [0, 0.8]`；`applyConfidence`（`effectiveWeight = feedbackWeight × confidence`，低置信度自动降权）；`applyGrayRatio` 全局灰度 |
| `ProfileVersionStore` | 见第 3 层 |
| `ProfileCache` | TTL 30s（`PROFILE_CACHE_TTL_MS=30_000L`）；`get` / `put` / `invalidate` |
| `CachedProfileLoader` | 异步刷新内存快照，避免业务侧读路径阻塞 |

灰度策略：`bucket = (sessionId.hashCode().absoluteMod(100)) / 100.0`，与 `ConfigRepository.getFeedbackGrayRatio` 比较判定是否对当前 session 生效。同一 sessionId 哈希稳定，保证 A/B 分组在多次会话间一致。

override 软删除：新 override 写入时，同 `(type, key)` 的旧 override 标记 `superseded=1`（不删除），形成可审计的覆盖链。`resetAll()` 才物理删除全部 override。

### 第 5 层 用户反馈智能体层

| 关键类 | 职责 |
| --- | --- |
| `FeedbackAgent` | 单轮 LLM 智能体；`FEEDBACK_AGENT_RATE_LIMIT_PER_HOUR=10`；`inFlightTimestamps ConcurrentLinkedQueue<Long>`（带时间戳的 in-flight 占位，避免无界增长）；`RECENT_FEEDBACK_LIMIT=10`、`RECENT_VERSIONS_LIMIT=10`；`handle()` / `confirmRollback()` |
| `FeedbackAgentPromptBuilder`（core-llm） | 9 种 Action schema；注入防护 `<<<USER_INPUT_START>>>` / `<<<USER_INPUT_END>>>`；`parse()` 宽松解析 + sanitize 过滤 |

智能体流程：用户在 `ProfileChatScreen` 发送消息 -> `ProfileChatViewModel.send()` -> `FeedbackAgent.handle()` 单轮 LLM 调用 -> 解析得到 `FeedbackAction` 列表 -> `RollbackProfileVersion` 类型的 Action 先生成预览（不直接应用），其他 Action 通过 `ProfileAdjuster.applyAll()` 写入 override 表 -> 用户在 `RollbackPreviewCard` 确认后调 `confirmRollback()` 触发 `ProfileVersionStore.applyRollback`。

限流：单小时内最多 10 次智能体调用，超过则拒绝。`inFlightTimestamps` 用时间戳占位而非简单计数，可滑动窗口清理过期时间戳。

## 模块边界

```text
+-------------------+        +-------------------+        +-------------------+
|   feature-profile | -----> | core-profiling    | -----> |     core-data     |
|  (ProfileChat /   |        | (Tracker /        |        | (Entity / DAO /   |
|   DevOptions /    |        |  Analyzer /       |        |  Repository /     |
|   ProfileInspect) |        |  FeedbackAgent)   |        |  domain model)    |
+-------------------+        +---------+---------+        +-------------------+
                                       |
                                       v
                             +-------------------+
                             |     core-llm      |
                             | (LlmClient /      |
                             |  PromptBuilder)   |
                             +-------------------+
                                       ^
                                       |
                             +-------------------+
                             | core-scheduler    |
                             | (UserProfileWorker|  触发第 3 层 LLM 画像周期生成
                             |  EventCleanup)    |  触发事件 TTL 清理
                             +-------------------+
```

依赖关系：

- `core-profiling` 依赖 `core-data`（Entity / DAO / Repository / domain model）与 `core-llm`（LlmClient、PromptBuilder）
- `core-profiling` **不依赖** `core-scheduler`；第 3 层 LLM 画像的周期触发由 `core-scheduler` 中的 `UserProfileWorker` 反向调用 `core-profiling` 的 `ProfileVersionStore` 与 `core-llm` 的 `UserProfilePromptBuilder` 完成
- `core-profiling` **不定义** `@Database` / `@Dao`，所有持久化通过 `core-data` 提供的 5 个 DAO（`UserActionDao` / `UserProfileDao` / `UserProfileOverrideDao` / `UserProfileFeedbackDao` / `UserProfileRollbackDao`）完成
- `core-profiling` 使用 `room-runtime` / `room-ktx` 仅为 `ProfileVersionStore.applyRollback` 等跨 DAO 多步写操作提供 `withTransaction` 包装，与 `core-data` 既有的事务模式一致

## ProfilingModule Hilt 装配

`core-profiling/src/main/java/com/trae/social/core/profiling/di/ProfilingModule.kt`：

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object ProfilingModule {

    @Provides @Singleton
    fun provideUserActionSink(dao: UserActionDao): UserActionSink = DaoUserActionSink(dao)

    @Provides @Singleton
    fun provideProfilingGate(configRepository: ConfigRepository): ProfilingGate =
        ConfigProfilingGate(configRepository)

    @Provides @Singleton
    fun provideUserActionTracker(
        sink: UserActionSink,
        gate: ProfilingGate,
    ): UserActionTracker = UserActionTrackerImpl(sink, gate)

    @Provides @Singleton
    fun provideUserProfileReadAccess(
        impl: UserProfileReadAccessImpl,
    ): UserProfileReadAccess = impl
}
```

装配要点：

- `@Module` `@InstallIn(SingletonComponent::class)`，全局单例
- `UserActionSink` 绑定 `DaoUserActionSink`（包 `UserActionDao.insertAll`），便于单测注入 fake
- `ProfilingGate` 绑定 `ConfigProfilingGate`，读 `ConfigRepository.isProfilingEnabled`
- `UserActionTracker` 绑定 `UserActionTrackerImpl`
- `UserProfileReadAccess` 绑定 `UserProfileReadAccessImpl`
- `FeedbackAgent` / `FeedbackController` / `ProfileAdjuster` / `ProfileVersionStore` / `ProfileCache` / `CachedProfileLoader` / `BasicProfileAnalyzer`(object) / `BasicProfileTrigger` 等通过 `@Inject constructor` 自动注入，无需 `@Provides`
- `DaoUserActionSink` / `ConfigProfilingGate` 为 `private class`，仅在 `ProfilingModule` 内部使用

`ConfigProfilingGate` 的 ANR 修复（第二轮 review Major 5）：

```kotlin
private class ConfigProfilingGate(
    private val configRepository: ConfigRepository,
) : ProfilingGate {
    @Volatile private var enabledCache: Boolean = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            while (true) {
                runCatching { enabledCache = configRepository.isProfilingEnabled() }
                    .onFailure { Timber.w(it, "刷新 profiling 开关失败,沿用上次缓存值") }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun isEnabled(): Boolean = enabledCache  // 永不阻塞调用线程
    override fun isDebug(): Boolean = false
    private companion object { const val REFRESH_INTERVAL_MS = 5_000L }
}
```

原实现 `isEnabled()` 在缓存过期时 `runBlocking` 读 DataStore，而 `UserActionTracker.trackNow` 被 `FeedViewModel` / `PublishViewModel` 等 UI 事件处理器在主线程直接调用，`runBlocking` + DataStore IO 触发 ANR。修复后改为后台协程 5s 周期刷新 `@Volatile enabledCache`，`isEnabled()` 仅读缓存，永不阻塞。

## 采集开关与灰度默认值

所有配置项由 `ConfigRepository` 统一对外，存储在 DataStore Preferences（`social_prefs`）。

| 配置项 | DataStore Key | 默认值 | 说明 |
| --- | --- | --- | --- |
| 用户行为采集总开关 | `KEY_PROFILING_ENABLED`（boolean） | `true` | 关闭后 `UserActionTracker` 停库 + 反哺降级 |
| 反哺全局灰度比例 | `KEY_FEEDBACK_GRAY_RATIO`（double） | `1.0`（`DEFAULT_FEEDBACK_GRAY_RATIO`） | 0.0 = 完全不反哺，1.0 = 全量反哺 |
| 用户反馈智能体开关 | `KEY_FEEDBACK_AGENT_ENABLED`（boolean） | `true` | 关闭后 `ProfileChatScreen` 智能体不可用 |
| 原始事件保留天数 | `KEY_EVENT_TTL_DAYS`（int） | `14`（`DEFAULT_EVENT_TTL_DAYS`） | `EventCleanupWorker` 据此清理 `user_action_events` |
| LLM 画像周期 | `KEY_PROFILE_PERIOD_HOURS`（int） | `48`（`DEFAULT_PROFILE_PERIOD_HOURS`） | 被 `AiActivityLevel` 实际档位覆盖 |

`ConfigRepository.companion` 中的常量（不可配置）：

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `COLD_START_THRESHOLD` | `50` | 冷启动事件数阈值 |
| `FEEDBACK_AGENT_RATE_LIMIT_PER_HOUR` | `10` | 智能体调用限流（次/小时） |
| `PROFILE_CACHE_TTL_MS` | `30_000L` | 读缓存 TTL（毫秒） |
| `MAX_PROFILE_VERSIONS` | `100` | 版本库上限（超限删最旧非激活版本） |

调试入口：`DevOptionsScreen` 的"画像调试开关"区块提供行为采集 Switch、反馈智能体 Switch、反哺灰度比例 Slider（`steps=9`，0%–100% 步进 10%），状态由 `DevOptionsViewModel` 的 `profilingEnabled` / `feedbackGrayRatio` / `feedbackAgentEnabled` 三个 StateFlow 暴露。

## 事件类型枚举（UserActionType）

`UserActionType` 定义在 `core-data` 模块（`com.trae.social.core.data.model.UserActionType`），按 `category` 分 8 组，共 34 个枚举值。持久化时以 `name` 存入 `user_action_events.type` 列。

| category | 枚举值 | 说明 |
| --- | --- | --- |
| session | `SESSION_START` / `SESSION_END` | 会话开始 / 结束，由 `SessionManager` 自动 track |
| browse | `SCREEN_ENTER` / `SCREEN_LEAVE` | 进入 / 离开页面 |
| browse | `TWEET_VIEW` | 推文进入视口（权重 1.0） |
| browse | `TWEET_DWELL` | 推文停留（权重 2.0，含 `durationMs`） |
| browse | `IMAGE_FULLSCREEN` | 图片全屏查看 |
| interaction | `TWEET_LIKE` / `TWEET_UNLIKE` | 点赞 / 取消点赞（权重 3.0） |
| interaction | `TWEET_COMMENT` | 评论（权重 5.0） |
| interaction | `TWEET_RETWEET` | 转发（权重 4.0） |
| interaction | `TWEET_BOOKMARK` / `TWEET_UNBOOKMARK` | 收藏 / 取消收藏（权重 6.0） |
| social | `FOLLOW` / `UNFOLLOW` | 关注 / 取消关注（权重 3.0） |
| publish | `PUBLISH_TWEET` | 发布推文（权重 6.0） |
| publish | `CAPTURE_PHOTO` | 拍照 |
| publish | `APPLY_FILTER` | 应用滤镜 |
| publish | `PUBLISH_MODE_SWITCH` | 切换发布模式（相机 / 编辑器） |
| navigation | `TAB_SWITCH` | Tab 切换 |
| navigation | `OPEN_SETTINGS` / `OPEN_APIKEY` / `OPEN_DEVOPTIONS` / `OPEN_FOLLOWLIST` / `OPEN_PROFILE_CHAT` | 打开各全屏页 |
| onboarding | `ONBOARDING_INTEREST_SELECTED` / `ONBOARDING_STEP` / `ONBOARDING_COMPLETE` / `ONBOARDING_SKIP` | 引导流程埋点 |
| feedback | `FEEDBACK_MESSAGE_SENT` | 用户在画像调校对话页发送消息 |
| feedback | `FEEDBACK_OVERRIDE_APPLIED` | 反馈智能体应用了 override |
| feedback | `FEEDBACK_OVERRIDE_RESET` | 用户清除全部 override |
| feedback | `FEEDBACK_VERSION_ROLLBACK_PREVIEW` | 回滚预览生成 |
| feedback | `FEEDBACK_VERSION_ROLLBACK_APPLIED` | 用户确认回滚 |

`fromName(name: String): UserActionType?` 用 `runCatching { valueOf(name) }.getOrNull()` 容错解析，未知 name 返回 null（升级期旧版本数据兼容）。

## 关键设计要点

- **5 层职责清晰**：捕获层只采集不分析；基础分析层纯函数无副作用；LLM 深度画像层周期触发；反哺层只调权重不改画像本体；智能体层接受用户反馈。各层可独立测试。
- **采集零阻塞**：`trackNow` fire-and-forget，Channel UNLIMITED 缓冲 + 批写，UI 主线程零等待；`ProfilingGate` 读缓存永不阻塞。
- **指数时间衰减 + 增量合并**：半衰期 7 天，旧事件权重自然衰减；`FULL_RECOMPUTE_INTERVAL_MS=6h` 内走增量合并，超时全量重算。
- **IQR 异常剔除**：剔除 1.5×IQR 外的极端停留时长等噪声，避免单次长停留污染画像。
- **指纹缓存复用**：输入指纹命中且无新反馈时跳过 LLM 调用，节省成本。
- **narrative 突变校验**：`jaccardSimilarity < 0.4` 回滚到旧版本，防止 LLM 偶发幻觉生成与历史割裂的画像叙事。
- **A/B 反哺效果回测**：`UserProfileAggregator.computeFeedbackEffect()` 遍历 1..8 调用 `queryScenarioEventsSince`，对带 `scenarioId` 打标的事件按 driven / control 两组计算 delta，闭环反馈反哺有效性。注意：只有接入 driven 路径并打标 `scenarioId` 的场景才会有非零 delta，见下方"反哺场景闭环状态"。
- **8 个反哺场景编号固定**：`topicBias` / `accountPriority` / `interactionAffinity` / `commentPersona` / `feedBoost` / `followRecommend` / `personaCoEvolve` / `interactionTiming`，编号 1-8，详见 [11 AI 调度系统详解](./11-AI-调度系统详解.md)。
- **反哺场景闭环状态**：issue #146 验收标准仅要求"至少落地场景 1/3/5"，本 PR 实际已闭环 5 个（1/3/4/6/7）超出验收要求；场景 2/5/8 状态见 [11 AI 调度系统详解 - 反哺场景闭环状态](./11-AI-调度系统详解.md#反哺场景闭环状态)，`computeFeedbackEffect` 对这 3 个场景恒得 delta=0（无 scenarioId 事件），不阻塞回测链路。
- **灰度稳定分组**：sessionId 哈希取模 100，同一 sessionId 多次会话分组不变，保证 A/B 一致性。
- **override 软删除 + 审计链**：新 override 写入时旧 override 标记 `superseded=1` 不删除，可追溯覆盖历史；`resetAll()` 才物理删除。
- **版本回滚不删除**：`applyRollback` 激活旧版本但不删除新版本，保留完整版本时间线，支持反复回滚。
- **单轮 LLM 智能体**：避免多轮上下文膨胀与成本失控；限流 10/小时；`RollbackProfileVersion` 需预览确认，不直接应用。
- **模块边界严格**：core-profiling 不定义 @Database/@Dao，不依赖 core-scheduler；LLM 周期触发由 core-scheduler 反向调用。

## 相关代码位置

- 捕获层：`core-profiling/.../capture/{UserActionTracker.kt, SessionManager.kt}`
- 基础分析层：`core-profiling/.../analysis/{BasicProfileAnalyzer.kt, BasicProfileTrigger.kt, EventTextPreParser.kt, UserProfileAggregator.kt}`
- 反哺层：`core-profiling/.../feedback/{FeedbackController.kt, ProfileAdjuster.kt, UserProfileReadAccess.kt, ProfileVersionStore.kt, ProfileCache.kt, CachedProfileLoader.kt}`
- 智能体层：`core-profiling/.../feedback/FeedbackAgent.kt`
- 映射：`core-profiling/.../mapping/ProfileMappers.kt`
- DI：`core-profiling/.../di/ProfilingModule.kt`
- 数据层：`core-data/.../entity/UserAction*.kt` / `UserProfile*.kt`、`dao/UserActionDao.kt` / `UserProfile*Dao.kt`
- LLM：`core-llm/.../prompt/{UserProfilePromptBuilder.kt, FeedbackAgentPromptBuilder.kt, CommentPromptBuilder.kt}`
- 调度：`core-scheduler/.../work/{UserProfileWorker.kt, EventCleanupWorker.kt}`
- UI：`feature-profile/.../ProfileChatScreen.kt` / `ProfileInspectViewModel.kt` / `DevOptionsScreen.kt`
- 详见 [05-core-data 数据层](./05-core-data-数据层.md)、[07-core-scheduler 调度层](./07-core-scheduler-调度层.md)、[12 LLM 集成与 Prompt 工程](./12-LLM-集成与-Prompt-工程.md)、[13 数据库设计](./13-数据库设计.md)。
