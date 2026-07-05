# Tasks

> 说明：每个 Task 末尾的"验收标准"用于 Sub-Agent 完成后自验；"注意事项"提示实现中需特别关注的风险点（对应 spec.md 的 RISK 章节）。

## 阶段 0：项目骨架

### Task 1: 初始化 Android 工程骨架
- [ ] SubTask 1.1: 创建多模块 Gradle 工程
  - 根目录 `build.gradle.kts`、`settings.gradle.kts`（含 `include` 全部 10 模块）、`gradle/libs.versions.toml`
  - `app` 模块（`com.android.application` + `kotlin-android` + `dagger.hilt`）
  - 4 个 core 模块：`core-designsystem`、`core-data`、`core-llm`、`core-scheduler`（`com.android.library`）
  - 5 个 feature 模块：`feature-feed`、`feature-timeline`、`feature-profile`、`feature-publish`、`feature-onboarding`
  - 所有模块统一 `namespace = "com.trae.social.<module>"`
- [ ] SubTask 1.2: 配置 `app/src/main/AndroidManifest.xml`
  - 权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`CAMERA`、`READ_MEDIA_IMAGES`（API 33+）、`READ_EXTERNAL_STORAGE`（API ≤32）、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`（API 34+）、`POST_NOTIFICATIONS`（API 33+）、`RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  - `<application>`：`android:name=".SocialApp"`、`allowBackup="false"`、`supportsRtl="true"`、`theme` 指向 Compose 主题
  - `<activity>` `MainActivity`：`exported=true`、`launchMode="singleTask"`、`windowSoftInputMode="adjustResize"`
  - `<service>` `SchedulerForegroundService`：`foregroundServiceType="dataSync"`
  - `<provider` `FileProvider` 用于相机拍照分享
- [ ] SubTask 1.3: 在 `libs.versions.toml` 集中声明依赖版本
  - Kotlin 2.0+、Compose BOM 2024.06+、Hilt 2.51+、Room 2.6+、Retrofit 2.11+、OkHttp 4.12+、Coil 2.6+、WorkManager 2.9+、CameraX 1.3+、DataStore 1.1+、Navigation Compose 2.7+、Paging 3 3.2+、Accompanist Permissions 0.34+、EncryptedSharedPreferences 1.1+、Kotlinx Coroutines 1.8+、Kotlinx Serialization 1.6+
- [ ] SubTask 1.4: 创建 `SocialApp` Application 类
  - `@HiltAndroidApp`
  - 实现 `Application()`，初始化 Timber 日志（debug 树 + release 树脱敏）
  - 配置全局 `Thread.setDefaultUncaughtExceptionHandler`，记录崩溃至 `scheduler_log`
  - 在 `onCreate` 中触发 WorkManager 初始化与调度恢复（详见 Task 8）
- [ ] SubTask 1.5: 配置 release 构建与签名
  - `app/build.gradle.kts`：`signingConfigs` 占位（从 `keystore.properties` 读取，文件不存在时使用 debug 签名 fallback）
  - `buildTypes.release`：`isMinifyEnabled=true`、`isShrinkResources=true`、`proguardFiles`（默认 + `proguard-rules.pro`）
  - `proguard-rules.pro`：保留 Hilt、Room、Retrofit、Kotlinx Serialization、Compose 相关规则
  - `splits.abi`：开启 `arm64-v8a` / `armeabi-v7a` / `x86_64` 拆分
  - 验证：`./gradlew assembleRelease` 产出 APK 至 `app/build/outputs/apk/release/`
- **验收标准**：`./gradlew assembleRelease` 成功产出 APK；`./gradlew assembleDebug` 可在模拟器安装启动（显示空白 Compose 界面即可）
- **注意事项**：RISK-10（APK 体积）、RISK-11（allowBackup=false）

## 阶段 1：设计系统与数据底座（可与阶段 2 并行起步）

### Task 2: 实现 Apple 风格设计系统（core-designsystem）
- [ ] SubTask 2.1: 定义 Color Tokens
  - `SocialColors.kt`：明色 `LightSocialColors`、深色 `DarkSocialColors`
  - 颜色：`systemBlue(#007AFF)`、`systemBackground(#FFFFFF/#000000)`、`secondaryBackground(#F2F2F7/#1C1C1E)`、`tertiaryBackground`、`label(#000000/#FFFFFF)`、`secondaryLabel`、`separator`、`systemRed/Green/Orange/Purple`
  - `@Composable fun socialColors(): SocialColors` 根据 `isSystemInDarkTheme()`
- [ ] SubTask 2.2: 定义 Typography
  - `SocialTypography.kt`：`largeTitle`、`title1`、`title2`、`title3`、`headline`、`body`、`callout`、`subheadline`、`footnote`、`caption1`、`caption2`
  - 字体回退链：`FontFamily.SansSerif`（系统默认即 SF 风格回退）
  - 中文场景下使用系统默认中文字体（不强制下载 SF Pro，减小体积）
- [ ] SubTask 2.3: 实现磨砂玻璃组件 `GlassBlurContainer`
  - API 31+：`Modifier.blur(radius)` + 半透明背景
  - API 26-30：降级为 `Color.surface.copy(alpha=0.85f)` 纯色半透明
  - 设备性能检测：`ActivityManager.isLowRamDevice` → 强制降级
  - 滚动时暂停模糊：`CompositionLocal` 提供 `LocalIsScrolling`，true 时 radius 减半
  - 模糊半径分级：高端 20dp、中端 10dp、低端 0dp
- [ ] SubTask 2.4: 实现通用组件
  - `Avatar(url, size, modifier)`：Coil 加载，圆形裁剪，placeholder shimmer
  - `ActionButton(text, icon, onClick)`：Capsule 形状，systemBlue 背景
  - `CapsuleTab(tabs, selectedIndex, onTabSelected)`：横向 Tab，选中态胶囊高亮
  - `SocialSheet(content)`：底部弹出 Sheet（`ModalBottomSheet` 包装）
  - `SocialCard`：圆角 12dp + 微阴影
  - `SocialDivider`：1px `separator` 色
  - `LoadingShimmer`：shimmer 占位
- [ ] SubTask 2.5: 实现 `AppTheme`
  - `@Composable fun SocialTheme(content)`：提供 `SocialColors`、`SocialTypography`、`MaterialTheme` 桥接
  - 状态栏色：`WindowCompat` 设置透明状态栏，图标色随主题
- **验收标准**：在 `app` 模块创建一个预览页，展示全部组件明/深色双模式截图；`./gradlew :core-designsystem:test` 通过
- **注意事项**：RISK-6（低端机性能）

### Task 3: 实现数据层（core-data）
- [ ] SubTask 3.1: 定义实体
  - `AccountEntity`：`id`、`displayName`、`username`、`avatarSeed`、`bio`、`profession`、`ageRange`、`culturalBackground`、`worldview`、`values`、`languageStyle`、`catchphrase`、`emojiPreference`(JSON)、`typoRate`、`activeWindows`(JSON, 24 槽 bool 数组)、`isVirtual`(bool)、`createdAt`、`updatedAt`、`dynamicLifeStory`、`dynamicWorkInfo`、`recentMood`
  - `TweetEntity`：`id`、`authorId`、`text`、`mediaPath`(nullable)、`mediaTheme`(nullable)、`createdAt`、`likeCount`、`commentCount`、`retweetCount`、`isAiGenerated`、`deduplicationKey`(unique)
  - `InteractionEntity`：`id`、`tweetId`、`accountId`、`type`(LIKE/COMMENT/RETWEET/FOLLOW)、`content`(nullable, 评论用)、`createdAt`、`scheduledAt`(预计触发时刻)、`executedAt`(nullable)
  - `FollowRelationEntity`：`followerId`、`followeeId`、`createdAt`（复合主键）
  - `PersonaDynamicFieldEntity`：`accountId`、`lifeStory`、`workInfo`、`relationshipNetwork`(JSON)、`mood`、`updatedAt`
  - `UserConfigEntity`：`key`、`value`（API Key、Base URL、默认提供商、引导标记、AI 活跃度档位）
  - `SchedulerLogEntity`：`id`、`timestamp`、`accountId`、`action`、`result`、`durationMs`、`errorMessage`
  - `ImageUsageEntity`：`accountId`、`imageHash`、`usedAt`（配图去重用）
- [ ] SubTask 3.2: 实现 DAO 与 Room 数据库
  - `AccountDao`：`upsertAll`、`getById`、`getVirtualAccounts`、`getActiveInHour(hour)`、`updateDynamicFields`
  - `TweetDao`：`insert`、`getFeed(page, size)`（按 `createdAt DESC` 分页）、`getByAuthor`、`getWithMedia`（时间线用）、`getByDeduplicationKey`
  - `InteractionDao`：`insert`、`getPendingBefore(time)`、`markExecuted`
  - `FollowRelationDao`、`UserConfigDao`、`SchedulerLogDao`、`ImageUsageDao`
  - `AppDatabase`：`@Database(entities=[...], version=1)`，`exportSchema=true`，schema JSON 输出至 `schemas/`
  - 索引：`TweetEntity.createdAt`、`TweetEntity.authorId`、`TweetEntity.deduplicationKey`（unique）、`InteractionEntity.scheduledAt`
- [ ] SubTask 3.3: 实现 Repository
  - `AccountRepository`：`getAccounts(page)`、`getById(id)`、`getActiveAccountsNow()`、`updateDynamicFields(id, fields)`
  - `TweetRepository`：`getFeedFlow()`（返回 `Flow<PagingData<Tweet>>`）、`insertTweet(tweet)`、`getMediaTweets()`、`getByAuthor(authorId)`
  - `InteractionRepository`：`scheduleInteraction(interaction)`、`getPendingInteractions()`
  - `ConfigRepository`：包装 DataStore，提供 `getApiKey(provider)`、`setApiKey(provider, key)`、`getDefaultProvider()`、`isOnboardingCompleted()`
- [ ] SubTask 3.4: 接入 DataStore 与 EncryptedSharedPreferences
  - 敏感数据（API Key）：`EncryptedSharedPreferences`，Master Key 通过 `MasterKey.Builder` + AES256_GCM
  - 非敏感配置（默认提供商、引导标记、AI 活跃度）：DataStore Preferences
  - `ConfigRepository` 统一对外，内部按字段类型路由
- [ ] SubTask 3.5: 实现种子数据加载器
  - `PersonaSeeder`：首次启动读取 `assets/personas/personas_*.json`（10 个分片，每片 20+ 条）
  - 使用 Kotlinx Serialization 解析，`upsertAll` 入库
  - 异步执行（`Dispatchers.IO`），不阻塞 UI；UI 显示进度（已导入 N/220）
  - 同时导入人设自带的"历史推文"（每账号 5-10 条，时间戳分布于启动前 1-30 天）—— 见 RISK-14
  - 幂等：导入前检查 `accounts` 表是否非空，非空则跳过
- **验收标准**：`./gradlew :core-data:test` 通过；单元测试覆盖 DAO 查询（用 in-memory DB）；首次启动导入 220 条人设 + ~1500 条历史推文耗时 < 5s
- **注意事项**：RISK-9（schema 迁移）、RISK-14（冷启动内容）、RISK-11（Key 加密）

## 阶段 2：LLM 与配图集成（依赖 Task 1）

### Task 4: 实现多 LLM 提供商客户端（core-llm）
- [ ] SubTask 4.1: 定义 `LlmClient` 接口
  - `suspend fun chat(messages: List<ChatMessage>, config: ChatConfig): Flow<String>`（流式，逐 token）
  - `suspend fun chatSync(messages: List<ChatMessage>, config: ChatConfig): String`（非流式）
  - `suspend fun ping(): Boolean`（连通性测试）
  - `data class ChatMessage(role: Role, content: String)`，`enum Role { SYSTEM, USER, ASSISTANT }`
  - `data class ChatConfig(temperature: Float, maxTokens: Int, jsonMode: Boolean)`
- [ ] SubTask 4.2: 实现 OpenAI 提供商
  - Retrofit 接口 `OpenAiApi`：`@POST("v1/chat/completions")` + `@Streaming` 流式
  - 端点：默认 `https://api.openai.com`，支持自定义 Base URL（如 Azure、代理）
  - SSE 解析：`data: {json}\n\n`，提取 `choices[0].delta.content`
  - JSON mode：`response_format={"type":"json_object"}`
  - 终止：`data: [DONE]`
- [ ] SubTask 4.3: 实现 Anthropic 提供商
  - Retrofit 接口 `AnthropicApi`：`@POST("v1/messages")` + `@Streaming`
  - Headers：`x-api-key`、`anthropic-version: 2023-06-01`
  - SSE 解析：多 event 类型 `message_start`、`content_block_delta`、`message_stop`，提取 `delta.text`
  - system prompt 作为顶层字段（非 messages 数组）
- [ ] SubTask 4.4: 实现 Google Gemini 提供商
  - Retrofit 接口 `GeminiApi`：`@POST("v1beta/models/{model}:streamGenerateContent")` + `@Streaming`
  - 鉴权：`?key=API_KEY` query 参数
  - 响应：HTTP chunked（非 SSE），每行一个 JSON 对象，提取 `candidates[0].content.parts[0].text`
  - 非流式：`:generateContent`
- [ ] SubTask 4.5: 实现 `LlmProviderRegistry`
  - `@Singleton`，持有各提供商实例（按需创建，懒加载）
  - `fun getClient(provider: LlmProvider): LlmClient`
  - `fun getDefaultClient(): LlmClient`（从 ConfigRepository 读默认）
  - 切换默认提供商时清空缓存实例
- [ ] SubTask 4.6: 实现连通性测试与全局拦截器
  - `ping()` 发送一条 `"ping"` 用户消息，期望非空响应即成功
  - 拦截器链：`AuthInterceptor`（注入 Key）→ `LoggingInterceptor`（仅 debug，Key 脱敏）→ `RetryInterceptor`（429/5xx 指数退避，最多 3 次）
  - 全局速率限制器 `RateLimiter`（令牌桶，30 RPM，可在设置调整）
- **验收标准**：单元测试用 MockWebServer 覆盖三家流式响应解析；连通性测试在真实 API Key 下通过
- **注意事项**：RISK-1（配额）、RISK-4（SSE 兼容）、RISK-11（Key 脱敏）、RISK-13（JSON mode 差异）

### Task 5: 实现 Prompt 工程
- [ ] SubTask 5.1: 推文生成 Prompt 模板
  - System：注入人设固定字段（worldview + values + languageStyle + catchphrase + emojiPreference + typoRate + profession + ageRange + culturalBackground）+ "你是该人物，以第一人称发布一条原创推文"
  - User：当前时段 + 最近 3 条该账号推文（避免重复）+ 最近情绪 + "输出 JSON：`{text, withImage, imageTheme, interactionTendency}`"
  - 约束：`text` ≤ 280 字符、`withImage` bool、`imageTheme` enum（风景/美食/城市/宠物/运动/艺术/无）、`interactionTendency` 0.0-1.0
  - 后处理：按 `typoRate` 随机注入错别字（替换表）、按 `emojiPreference` 末尾追加 emoji
- [ ] SubTask 5.2: 评论生成 Prompt
  - System：评论者人设
  - User：被评推文 + 原作者人设简介 + "生成一条评论，≤100 字符"
  - 批量化：一次调用生成 3-5 条不同账号的评论（Prompt 中列出多个评论者人设）
- [ ] SubTask 5.3: 人设动态字段更新 Prompt
  - System：通用
  - User：账号当前动态字段 + 最近一周该账号的推文与互动事件 + "更新 lifeStory、workInfo、mood，保持人设一致性"
  - 差异校验：新旧字段 embedding cosine > 0.5（防止突变），否则回退
- **验收标准**：单元测试覆盖 Prompt 模板渲染（不同人设输入产出不同 system prompt）；JSON 解析失败的降级路径测试
- **注意事项**：RISK-2（人设漂移）、RISK-13（JSON 解析）

### Task 6: 实现虚拟账号配图本地图库
- [ ] SubTask 6.1: 整理静态图片集
  - 主题分类：`landscape`（风景）、`food`（美食）、`city`（城市）、`pet`（宠物）、`sport`（运动）、`art`（艺术）、`tech`（科技）、`nature`（自然）
  - 每类至少 25 张，总计 ≥ 200 张
  - 格式：WebP，质量 80，单张 < 100KB，分辨率 1080×1080 或 1080×1350
  - 存放：`app/src/main/assets/gallery/<theme>/<n>.webp`
  - 索引：`assets/gallery/index.json`（`{theme: [filename, ...]}`）
- [ ] SubTask 6.2: 实现 `LocalImageGallery` 查询接口
  - `suspend fun pickRandom(theme: String, accountId: String): String`（返回 asset 路径）
  - 去重：查询 `ImageUsageEntity`，排除该账号 30 天内用过的图片哈希
  - 记录：选取后写入 `ImageUsageEntity`
  - 主题 fallback：指定主题图片耗尽时，从相邻主题选取
- [ ] SubTask 6.3: 接入推文生成流程
  - `TweetGenerationWorker` 调用 LLM 后，若 `withImage=true`，调用 `LocalImageGallery.pickRandom(imageTheme, accountId)`
  - 将 asset 路径写入 `TweetEntity.mediaPath`，`mediaTheme` 写入主题
- **验收标准**：单元测试覆盖 `LocalImageGallery` 去重逻辑；图库总体积 < 20MB
- **注意事项**：RISK-10（APK 体积）

## 阶段 3：虚拟账号人设数据（依赖 Task 3）

### Task 7: 生成 200+ 虚拟账号人设种子
- [ ] SubTask 7.1: 编写人设生成脚本（`tools/persona-gen/`）
  - Python 脚本，输出 220 条人设
  - 矩阵分布：职业 × 年龄段 × 文化背景
    - 职业 20 种（程序员/设计师/教师/医生/律师/记者/厨师/摄影师/音乐人/作家/运动员/创业者/公务员/护士/工程师/研究员/学生/退休者/艺术家/自由职业）
    - 年龄段 4 档（18-24/25-34/35-44/45+）
    - 文化背景 6 种（华北/华东/华南/西南/西北/海外华人）
    - 矩阵产生 480 槽位，按比例抽样 220 个
  - 每条人设的固定字段由模板 + 变体组合生成
- [ ] SubTask 7.2: 填充固定字段
  - `worldview`：基于职业 + 年龄段的模板，随机选 1 个变体（如程序员："代码即诗，简洁是终极复杂"）
  - `values`：基于文化背景 + 职业的模板（如教师："传道授业，育人即育己"）
  - `languageStyle`：随机选 1 种（正式/口语/幽默/犀利/温和/文艺/直率）
  - `catchphrase`：基于人设生成 1-2 个口癖（如"破防了"、"绝绝子"、"笑死"、"格局打开"）
  - `emojiPreference`：基于年龄段选 emoji 列表（18-24 多 emoji，45+ 少 emoji）
  - `typoRate`：随机 0.0-0.05（年轻账号偏高）
  - `activeWindows`：基于职业生成（如教师 7-12、14-22；程序员 9-13、14-18、20-24；退休者 6-10、14-20）
- [ ] SubTask 7.3: 生成头像
  - 使用确定性 Avatar 生成器（如基于 `avatarSeed` 的 DiceBear API 离线 SVG → PNG，或纯 Compose 生成的几何头像）
  - 不依赖任何 AI 图像生成 API（用户反馈明确要求）
  - 头像落盘至 `assets/avatars/<accountId>.png`，< 5KB/张
- [ ] SubTask 7.4: 生成历史推文种子
  - 每账号 5-10 条历史推文，文本由模板 + 人设字段组合（不调用 LLM，节省成本）
  - 时间戳：均匀分布在启动前 1-30 天
  - 30% 概率带配图（从本地图库随机选）
- [ ] SubTask 7.5: 输出与校验
  - 输出 `assets/personas/personas_001.json` ~ `personas_011.json`（每片 20 条）
  - 输出 `assets/personas/index.json`（账号 ID 列表）
  - 校验脚本 `tools/persona-gen/validate.py`：
    - 数量 ≥ 220
    - `username` 唯一
    - `worldview + values + languageStyle` 三元组无完全重复
    - 所有必要字段非空
- **验收标准**：`python tools/persona-gen/validate.py` 全部通过；人设 JSON 总体积 < 1MB
- **注意事项**：RISK-5（人设生成）、RISK-14（历史推文营造账号早已存在）

## 阶段 4：AI 调度系统（依赖 Task 3、4、5、6、7）

### Task 8: 实现 AI 调度引擎（core-scheduler）
- [ ] SubTask 8.1: 定义调度规则模型与时间窗解析
  - `data class ScheduleRule(accountId, activeWindows: List<Boolean>, postsPerWindow: Int)`
  - `fun nextTriggerTime(rule, now): Instant`：在当前活跃窗内随机一刻，若窗已过则下个窗
  - `fun missedWindows(rule, lastRun, now): List<TimeWindow>`：补发用
- [ ] SubTask 8.2: 实现 `TweetGenerationWorker`
  - 输入：`accountId`、`deduplicationKey`
  - 流程：取账号人设 → 调 `LlmClient.chat`（Task 5 模板）→ 解析 JSON → 后处理（错别字/emoji）→ 若 `withImage` 调 `LocalImageGallery` → 写 `TweetEntity` → 写 `SchedulerLogEntity`
  - 失败重试：`BackoffPolicy.EXPONENTIAL`，10s/30s/90s，最多 3 次；429 直接跳过
  - 幂等：`deduplicationKey` 唯一约束，重复插入捕获 `SQLiteConstraintException` 静默处理
- [ ] SubTask 8.3: 实现 `InteractionWorker`
  - 触发：用户发布推文后，`InteractionRepository.scheduleInteraction` 排程 N 条互动（3-8 个账号 × 各 1 互动）
  - 延迟：按对数正态分布（点赞 30s-5min、评论 2-15min、转发 5-30min）
  - 执行：取评论者人设 → 调 `LlmClient.chat`（Task 5.2 模板）→ 写 `InteractionEntity`，更新 `TweetEntity.likeCount/commentCount`
  - AI 账号之间互动：每日扫描一次，为 5-10 条热门推文排程 AI 之间互动
- [ ] SubTask 8.4: 实现 `PersonaUpdateWorker`
  - 每周一次（`PeriodicWorkRequest`，7 天周期）
  - 随机选 20 个账号，调用 Task 5.3 模板更新动态字段
  - 差异校验防突变（RISK-2）
- [ ] SubTask 8.5: 实现前台服务与调度恢复
  - `SchedulerForegroundService`：`foregroundServiceType=dataSync`，常驻通知"社交生态运行中"
  - `SocialApp.onCreate` 启动服务 + 调度恢复：
    - 扫描 `missedWindows`，为错过的活跃窗补发推文（每窗最多补 1 条，避免轰炸）
    - 重新入队 `InteractionWorker`（已 schedule 但未执行的）
  - `BootReceiver`：开机后延迟 30s 启动服务
- [ ] SubTask 8.6: 实现限流与去重
  - 全局令牌桶 `RateLimiter`（30 RPM，可配）
  - 单账号每日上限：4 条推文（计数查 `TweetEntity` 当日记录）
  - 用户可调"AI 活跃度"档位：低（默认 RPM 10、每账号 2 条/日）、中（30、4）、高（60、8）
  - 调度去重：`deduplicationKey = accountId + windowStart + sequenceNo`
- **验收标准**：单元测试覆盖时间窗解析、令牌桶、幂等去重；集成测试在 Mock LLM 下完成"调度→生成→落库"全链路
- **注意事项**：RISK-1（配额）、RISK-3（后台调度）、RISK-15（可观测性）

## 阶段 5：UI 功能实现（依赖 Task 2、3）

### Task 9: 实现首次启动引导（feature-onboarding）
- [ ] SubTask 9.1: 引导首页
  - 全屏插画（用 Compose 绘制几何图形即可，避免下载图片）
  - 标题"欢迎使用"+ 功能简介（3 条要点）
  - 按钮"开始配置" / "稍后"
- [ ] SubTask 9.2: 提供商选择页
  - 4 个卡片：OpenAI / Anthropic / Gemini / 自定义（OpenAI 兼容）
  - 每卡片含 logo（用文字 + 色块代替，避免版权图片）、简介
  - 单选，"下一步"
- [ ] SubTask 9.3: API Key 与 Base URL 输入页
  - `OutlinedTextField`：API Key（默认密码模式，可切换显示）
  - Base URL：可选，根据所选提供商预填默认值
  - 模型名：可选，预填推荐模型（如 `gpt-4o-mini`）
  - 输入校验：Key 非空、URL 格式合法
- [ ] SubTask 9.4: 连通性测试页
  - 点击"测试连接"调 `LlmClient.ping()`
  - 状态：测试中（loading）→ 成功（绿色对勾）/ 失败（红色错误 + 具体原因）
  - 失败时显示错误码（401 / 超时 / DNS）+ "重试" / "返回修改"
- [ ] SubTask 9.5: 完成页与跳过逻辑
  - 成功页：保存配置 → 触发冷启动内容填充（RISK-14）→ "进入应用"
  - 跳过：标记 `onboardingSkipped=true`，进入主界面，首页顶部 banner 提示"前往设置配置 API Key 以启用 AI"
  - 首次启动免责声明（RISK-12）：进入引导前展示
- **验收标准**：UI 测试覆盖引导全流程（含跳过、失败重试）；配置正确保存至 EncryptedSharedPreferences
- **注意事项**：RISK-11（Key 加密）、RISK-12（免责声明）、RISK-14（冷启动填充）

### Task 10: 实现主框架与底部导航
- [ ] SubTask 10.1: `MainActivity` + Compose NavHost
  - `@AndroidEntryPoint`
  - `NavHost(startDestination = if (onboardingDone) "main" else "onboarding")`
  - `main` 路由：`Scaffold` + 底部栏 + 内容区嵌套 `NavHost`（feed/timeline/profile）
- [ ] SubTask 10.2: 底部 iOS 26 风格磨砂玻璃 Tab 栏
  - `BottomBar` Composable：`GlassBlurContainer` 包裹
  - 三个 Tab：首页（house 图标）、时间线（grid 图标）、我的（person 图标）
  - 选中态：图标填充 + 标签下方小圆点
  - 高度：56dp，含底部安全区 padding
- [ ] SubTask 10.3: Tab 栏右侧独立等高发布按钮
  - 浮动按钮，与 Tab 栏等高，圆形或胶囊形
  - 图标：相机 / 加号
  - 位置：Tab 栏右侧 16dp 间距
  - 点击导航至 `publish` 路由（独立 Activity 风格全屏）
- [ ] SubTask 10.4: 页面切换转场
  - Tab 间切换：无动画或淡入淡出
  - 进入发布页：从底部向上滑入（slideVertically）
  - 返回：向下滑出
- **验收标准**：UI 测试覆盖 Tab 切换、发布按钮导航、状态保持（滚动位置）
- **注意事项**：RISK-6（磨砂玻璃性能）

### Task 11: 实现首页信息流（feature-feed）
- [ ] SubTask 11.1: 推文卡片组件 `TweetCard`
  - 顶部行：Avatar（36dp）+ 显示名（粗体）+ 用户名（灰色）+ · + 时间 + 更多按钮（右侧）
  - 文本：`Text`，超 280 字符折叠 + "展开全文"
  - 图片：`AsyncImage`（Coil），圆角 12dp，最大高度 400dp，点击进入大图
  - 互动栏：评论/转发/点赞/收藏 四按钮（图标 + 数字）
  - AI 推文标识：头像右下角小蓝点（RISK-12）
- [ ] SubTask 11.2: `FeedViewModel` + 分页
  - Paging 3 `PagingSource` 包装 `TweetDao.getFeed`
  - `StateFlow<UiState>`：Loading / Success / Error / Empty
  - ViewModel 注入 `TweetRepository`
- [ ] SubTask 11.3: 下拉刷新 + 上拉加载
  - `PullRefreshIndicator` + `collectAsLazyPagingItems()`
  - 上拉触发 `loadMore`，无更多时 footer "已加载全部"
- [ ] SubTask 11.4: 互动交互
  - 点赞：本地立即 +1（乐观更新），后台写 `InteractionEntity`
  - 评论：点击弹 Sheet（SubTask 11.5）
  - 转发：确认弹窗 → 写 `TweetEntity`（转发属性）
  - 收藏：本地切换状态
- [ ] SubTask 11.5: 评论弹层
  - `ModalBottomSheet`，顶部原推文摘要 + 评论列表 + 底部输入框
  - 评论列表复用 `TweetCard` 简化版
  - 发送评论：写 `InteractionEntity`，更新 `commentCount`
- [ ] SubTask 11.6: 图片大图查看器
  - 全屏 Activity 或 Dialog
  - `ZoomableImage`：自实现 `Modifier.transformable` 双指缩放 + 双击切换 1x/3x
  - 左右滑动切换同推文多图（暂只支持单图，预留扩展）
- **验收标准**：UI 测试覆盖卡片渲染、点赞乐观更新、评论发送；滚动帧率 > 50fps（中端机）
- **注意事项**：RISK-6（滚动性能）

### Task 12: 实现时间线页面（feature-timeline）
- [ ] SubTask 12.1: 朋友圈式布局
  - 顶部：用户头像 + 昵称 + "我的相册"
  - 按日期分组：日期标题（如"7月5日"）+ 该日图片网格
  - 单日 1 张：大图；2 张：并排；3 张：1 大 + 2 小；4+ 张：3×N 网格
  - 每张图下方：发布时间 + 文本摘要（1 行）
- [ ] SubTask 12.2: 大图浏览器
  - 点击图片进入全屏 `ZoomableImage`
  - 左右滑动切换同日图片
  - 顶部：日期 + 关闭按钮
- [ ] SubTask 12.3: ViewModel 与数据源
  - `TimelineViewModel`：`getMediaTweetsFlow()` 按 `createdAt DESC`
  - 按日期分组在 ViewModel 内完成
  - 空状态：插画 + "去发布第一条带图推文"按钮 → 跳转发布页
- **验收标准**：UI 测试覆盖 1/2/3/4+ 张图片布局、空状态、大图浏览
- **注意事项**：无特殊风险

### Task 13: 实现我的页面（feature-profile）
- [ ] SubTask 13.1: 个人资料卡片
  - 顶部 banner（渐变色块）+ 头像（80dp，圆形，叠在 banner 上）+ 显示名 + 用户名 + bio
  - 统计行：发布数 / 关注数 / 粉丝数（点击进入对应列表）
  - "编辑资料"按钮
- [ ] SubTask 13.2: 我的推文列表
  - Tab：推文 / 媒体 / 喜欢
  - 推文 Tab：复用 `TweetCard` + `LazyColumn`
  - 媒体 Tab：复用时间线网格
  - 喜欢 Tab：展示用户点赞过的推文
- [ ] SubTask 13.3: 设置入口
  - 列表项：API Key 管理、人设管理（查看 200+ 虚拟账号）、AI 活跃度、清除缓存、关于、开发者选项（连点 7 次）
  - API Key 管理：复用引导页的输入 + 测试流程
  - 开发者选项：调度日志、手动触发调度、LLM 调用统计（RISK-15）
- [ ] SubTask 13.4: 关注 / 粉丝列表页
  - 列表展示 Avatar + 名称 + bio + 关注按钮
  - 关注按钮：未关注 → "关注"（蓝），已关注 → "已关注"（灰，点击取消）
- **验收标准**：UI 测试覆盖资料展示、Tab 切换、设置入口；编辑资料保存生效
- **注意事项**：无特殊风险

### Task 14: 实现相机式发布界面（feature-publish）
- [ ] SubTask 14.1: 顶部胶囊形横向 Tab
  - `CapsuleTab`：两个 Tab "相机" / "编辑器"
  - 选中态：胶囊高亮 + 文字加粗
  - 切换时内容区淡入淡出
- [ ] SubTask 14.2: 相机模式
  - CameraX `PreviewView` 全屏取景
  - 比例切换：底部三个按钮 1:1 / 4:3 / 16:9，更新 `ImageCapture` 与 `Preview` 的 `aspectRatio`
  - 拍照按钮：底部居中圆形，点击捕获 JPEG → 落盘 `cacheDir/capture/<timestamp>.jpg`
  - 切换前后摄：右上角按钮
  - 权限处理：`rememberPermissionState(CAMERA)`，未授权显示请求卡片
- [ ] SubTask 14.3: 底部实时预览
  - 拍照后缩略图（64dp 圆角）显示在底部
  - 多张支持：横向滑动列表，最多 4 张
  - 点击缩略图：进入编辑（裁剪/滤镜）或删除
- [ ] SubTask 14.4: 缩小飞入信息流动画
  - 点击预览照片 + "发布"按钮
  - 动画：`AnimatedContent` + `Modifier.graphicsLayer` 实现 scale 1→0.2 + 位移至屏幕顶部
  - 时长 400ms，`FastOutSlowInEasing`
  - 动画结束导航回首页，新推文已置顶
  - 降级：若动画实现复杂，使用 fade + scale 简化（RISK-8）
- [ ] SubTask 14.5: 图片顶部文本输入
  - 预览照片上方 `TextField`：placeholder "说点什么..."
  - 字数计数 280/280
  - 文本与图片路径一起作为发布参数
- [ ] SubTask 14.6: 编辑器模式
  - 相册选择：`ActivityResultContracts.PickVisualMedia`
  - 裁剪：自实现 uCrop-like 简化版（拖拽矩形）
  - 滤镜：3-5 个预设（原图/暖色/冷色/黑白/复古），用 `ColorMatrix` 实现
- [ ] SubTask 14.7: 发布逻辑
  - 写 `TweetEntity`（`authorId` = 用户 ID，`isAiGenerated=false`）
  - 触发 `InteractionRepository.scheduleInteractionsForUserTweet`（Task 8.3）
  - 返回首页，信息流刷新出新推文
- **验收标准**：UI 测试覆盖拍照、比例切换、文本输入、发布后信息流出现新推文；权限缺失场景
- **注意事项**：RISK-7（CameraX 兼容）、RISK-8（动画）

## 阶段 6：集成、测试与交付

### Task 15: 端到端集成与拟真度调优
- [ ] SubTask 15.1: 联调全链路
  - 引导配置 → 冷启动填充 → 信息流展示 → AI 调度生成新推文 → 用户发布 → AI 互动
  - 验证时间窗触发、互动延迟、配图选取
- [ ] SubTask 15.2: 拟真度调优
  - 调整错别字率、emoji 频率、互动延迟分布
  - 对比真实 X / Twitter 截图，调整卡片间距、字号、配色
  - 邀请 3-5 名内测用户主观评分（1-5 分），目标 ≥ 4 分
- [ ] SubTask 15.3: 异常处理
  - API Key 失效：信息流顶部 banner 提示
  - 网络断开：信息流继续展示本地数据，发布推文本地暂存
  - LLM 限流：调度降频，通知栏提示
- [ ] SubTask 15.4: 性能优化
  - `LazyColumn` 使用 `key` 参数稳定
  - Coil 配置：内存缓存 25% heap，磁盘缓存 100MB
  - 数据库查询加索引（已在 Task 3.2）
  - 启动优化：`AppComponent` 拆分，延迟非关键初始化
- **验收标准**：端到端冒烟测试通过；中端机滚动帧率 > 50fps；冷启动 < 2s
- **注意事项**：RISK-2（拟真度）、RISK-15（可观测性）

### Task 16: 测试
- [ ] SubTask 16.1: 单元测试
  - core-data：DAO 查询（in-memory DB）、Repository
  - core-llm：各提供商 SSE 解析（MockWebServer）、`LlmProviderRegistry` 切换
  - core-scheduler：时间窗解析、令牌桶、幂等去重
  - core-designsystem：无（纯 UI，由 UI 测试覆盖）
- [ ] SubTask 16.2: UI 测试
  - 引导流程、Tab 导航、信息流滚动、发布流程、评论弹层
  - 使用 `createAndroidComposeRule<MainActivity>`
- [ ] SubTask 16.3: 人设数据校验测试
  - 运行 `tools/persona-gen/validate.py` 作为 gradle task
  - 测试：加载 personas.json，断言 ≥ 220 条、username 唯一、字段完整
- [ ] SubTask 16.4: 集成测试
  - Mock LLM 环境下，调度→生成→落库→UI 展示全链路
- **验收标准**：测试覆盖率核心模块 ≥ 70%；`./gradlew test` 全绿
- **注意事项**：无特殊风险

### Task 17: 构建 release APK
- [ ] SubTask 17.1: 配置 release 签名
  - `keystore.properties` 模板（gitignore），文档说明用户自行生成 keystore
  - 默认 fallback：使用 debug keystore 产出可安装 APK（仅用于本交付）
- [ ] SubTask 17.2: 执行 `./gradlew assembleRelease`
  - 产出 ABI 拆分 APK（arm64-v8a 优先）
  - 验证体积 ≤ 50MB（RISK-10）
  - 验证 R8 混淆生效（反编译检查）
- [ ] SubTask 17.3: 安装冒烟测试
  - 在 Android 12 / 13 / 14 模拟器各安装一次
  - 完成引导（用真实或 mock API Key）→ 浏览信息流 → 发布推文 → 查看时间线与我的
  - 记录任何崩溃至 `scheduler_log`
- **验收标准**：APK 成功安装并完成冒烟测试；交付 APK 文件路径
- **注意事项**：RISK-10（体积）、RISK-11（签名）

# Task Dependencies
- Task 2、3、4、5 可在 Task 1 完成后并行启动
- Task 6（本地图库）独立，可与 Task 2-5 并行
- Task 7 依赖 Task 3（人设表结构）
- Task 8 依赖 Task 3、4、5、6、7
- Task 9、10、11、12、13、14 依赖 Task 2、3；Task 10 优先（其他 UI 页面挂载于其 NavHost）
- Task 14 依赖 Task 11（发布后进入信息流）
- Task 15 依赖所有前序任务
- Task 16 可与 Task 15 并行
- Task 17 依赖 Task 15、16
