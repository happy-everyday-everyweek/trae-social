# 架构设计

本文档描述 Trae Social 的模块划分、依赖关系与关键设计决策。

## 模块依赖图

```
                          ┌─────────┐
                          │   app   │  Application / MainActivity / 导航 / DI 装配
                          └────┬────┘
        ┌──────────┬──────────┼──────┬──────────┬──────────┐
        ▼          ▼          ▼      ▼          ▼          ▼
   feature-feed  timeline  profile  publish  onboarding  core-designsystem
        │          │       │  │     │  │          │       (独立叶子)
        │          │       │  └─────┼──┼──────────┘
        │          │       │        │  │
        │          │       │  ┌─────┘  │
        │          │       │  │        │
        ▼          ▼       ▼  ▼        ▼
  core-scheduler  │  core-profiling  core-llm ── implementation ──> core-data
        │         │       │            │
        └─────────┴───────┴────────────┘
                      core-data (叶子)
```

> 注：上图简化了 feature → core 的依赖关系，并非所有 feature 都依赖所有 core 模块。
> 精确依赖见下方「依赖规则」与各模块 `build.gradle.kts`。

依赖规则：
- `app` 模块依赖所有 feature 与 core 模块，负责 DI 装配与导航
- feature 模块仅依赖 core 模块，**不直接依赖其他 feature 模块**
- `feature-profile` 与 `feature-publish` 直接依赖 `core-scheduler`（引用 `WorkerPolicies` / `WorkerTags` / `UserProfileWorker` / `PendingInteractionWorker` / `PersonaUpdateWorker`）
- `core-scheduler` 依赖 `core-data`（DAO / Repository）、`core-llm`（LlmClient / RulesetEngine）与 `core-profiling`（SessionManager / UserActionTracker / FeedbackController / UserProfileReadAccess）
- `core-profiling` 依赖 `core-data` 与 `core-llm`（FeedbackAgent 依赖 LlmClient 与 PromptBuilder）
- `core-llm` 通过 `implementation(project(":core-data"))` 依赖 `core-data`，复用 `LlmProtocol` / `ModelCapability` / `LlmEndpointEntity` 等领域与持久化类型（#307：原 `api` 是为让旧 `LlmProvider` 作为 `LlmClient.provider` 公开类型暴露，#151 重构后 LlmClient 已无 provider 字段，故降级）
- `core-data` 与 `core-designsystem` 为叶子模块，不依赖其他项目模块

## 各模块职责

### app

应用入口模块。`SocialApp`（Application）负责 Timber 初始化、CrashHandler、PersonaSeeder、SchedulerInitializer 调用。`MainActivity` 持有 NavHost，管理三页主架构导航与全屏页路由。DI 模块（AssetProviderModule、AppLlmModule、AppOnboardingModule）提供跨模块绑定。

### core-designsystem

Apple 风格设计系统。包含 SocialColors / SocialTypography / SocialTheme（深色模式适配），以及可复用组件：GlassBlurContainer（磨砂玻璃，低版本降级为半透明纯色）、Avatar、ActionButton、SocialCard、LoadingShimmer、CapsuleTab、SocialDivider、SocialSheet。

### core-data

数据层。Room 数据库（version 8，含 17 个 Entity：Account / Tweet / Interaction / FollowRelation / PersonaDynamicField / SchedulerLog / ImageUsage / UserConfig / AccountActiveHour / Comment / UserActionEvent / UserProfileSnapshot / UserProfileVersion / UserProfileOverride / UserProfileFeedback / UserProfileRollback / LlmEndpoint），对应 DAO 与 Repository。TypeConverters 处理 List<String> 与枚举序列化。EncryptedSharedPreferences 存储 API Key 等敏感配置，DataStore Preferences 存储非敏感配置。PersonaSeeder 在首次启动时从 assets 加载 200+ 虚拟人设种子数据。

### core-llm

LLM 客户端层（#151 重构后）。以 **端点（Endpoint）** 为核心寻址单位，取代旧 `LlmProvider`-keyed 寻址：`EndpointRegistry` 按 endpointId 懒创建并缓存 `LlmClient` 实例，`EndpointConfigProvider` 提供端点配置与 API Key 读取，`DefaultRulesetEngine` 统一 `chatSync` / `ping` 入口。底层按 `LlmProtocol`（OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE）分派到 OpenAI / Anthropic 官方 Java SDK 实现的 `OpenAiCompatibleClient` / `AnthropicCompatibleClient`，Gemini 走 OpenAI 兼容端点。`EndpointRegistry` 通过订阅 `EndpointConfigProvider.observeEndpointChanges` 自动 `invalidateCache`，端点 CRUD / API Key 变更后缓存自动失效。`ModelCapability` 集合（TEXT / JSON_MODE_NATIVE / STREAMING / ...）声明每个端点的能力，`DefaultRulesetEngine` 据此选择调用路径。Prompt 工程含 TweetPromptBuilder / CommentPromptBuilder / PersonaUpdatePromptBuilder / FeedbackAgentPromptBuilder / UserProfilePromptBuilder，TweetPostProcessor 做错别字与截断后处理。

### core-scheduler

AI 调度层。基于 WorkManager，包含四类 Worker：
- **TweetGenerationWorker**：按账号活跃窗生成推文，含去重键、每日配额检查、429 跳过
- **InteractionWorker**：为指定推文生成 AI 互动（点赞 / 评论 / 转发 / 关注）
- **PendingInteractionWorker**：周期（15 分钟）扫描待互动推文
- **PersonaUpdateWorker**：周期（按 AI 活跃度档位缩放：LOW=14 天 / MEDIUM=7 天 / HIGH=3 天）更新虚拟账号动态字段

ScheduleRuleResolver 计算错过的活跃窗（调度恢复）与下次触发时间，支持每账号独立时区。SchedulerRateLimiter（复用 core-llm 的 RateLimiter）按档位限流。DailyQuotaChecker 按账号时区检查每日推文配额。SchedulerForegroundService 保活调度。BootReceiver 开机自启。档位变更通过 ConfigRepository.activityLevelChanges SharedFlow 通知 SchedulerInitializer 以 REPLACE 策略重排周期 Worker。

### feature-feed

首页信息流。FeedViewModel 使用 Paging 3 分页加载，authorCache（ConcurrentHashMap）缓存账号信息避免重复查库。TweetCard 渲染推文（含作者信息、配图、互动按钮），FullScreenImage 支持双指缩放与平移钳制。OnboardingSkippedBanner 在用户跳过引导时提示补全 API Key。

### feature-timeline

朋友圈式图片时间线。按时间分组，1/2/3/4+ 张图片差异化网格布局。使用 SVG ImageLoader 解码虚拟账号头像。

### feature-profile

个人主页。展示账号信息、推文列表（真实计数）、关注 / 粉丝列表（Paging）。SettingsScreen 提供 AI 活跃度档位切换（LOW / MEDIUM / HIGH），切换后通过 SharedFlow 触发调度器重排。ApiKeyManagementScreen 管理各 LLM 提供商的 API Key / Base URL / 模型名，保存后失效 LlmClient 缓存。DevOptionsScreen 提供开发者选项。

### feature-publish

相机式发布。CameraModeContent 使用 CameraX 绑定生命周期，支持 1:1 / 4:3 / 16:9 比例切换与正方形裁剪。EditorModeContent 提供滤镜（含动态 inSampleSize 与 Bitmap recycle）。CapturePreviewBar 展示已拍照片缩略图，支持多图与删除。PublishViewModel 通过 Channel 发送发布事件，支持成功 / 失败状态。

### feature-onboarding

首次启动引导。五步流程：欢迎 → 选择 LLM 提供商 → 输入 API Key → 测试连接 → 完成。OnboardingViewModel 管理引导状态，保存配置后由 `ConfigRepository` 写操作自动触发 `_endpointChanges` 事件流使 `EndpointRegistry` 自动失效缓存（#288，无需手动调 invalidateCache），并调用 `ColdStartFiller` 触发冷启动内容填充。支持跳过引导（设置 skipped 标记，FeedScreen 展示提示 banner）。

## 关键设计决策

### 调度恢复

SchedulerInitializer 在应用启动时扫描所有虚拟账号的错过的活跃窗（自上次运行起），为每个错过的窗口补发 1 条推文（每窗最多 1 条避免轰炸）。去重键（accountId_windowStart_sequenceNo）保证幂等。

### 429 限流处理

`DefaultRulesetEngine` 把 SDK 抛出的 429 异常转换为 `RateLimitedException`（继承 `IOException`，避免 OkHttp `AsyncCall` 重抛闪退）向上传递，Worker 捕获后返回 `Result.success()` 跳过重试，避免浪费 API 配额。HTTP 4xx（非 429）视为持久性错误直接抛出不降级，5xx 与 IO 错误使用 `BackoffPolicy.EXPONENTIAL` 指数退避（10s 起步，最多 3 次，由 WorkManager 自动调度）。

### 多 LLM 端点

#151 重构后，LLM 配置以 **端点（Endpoint）** 为核心：`LlmEndpointEntity` 持久化于 Room（`llm_endpoints` 表），每个端点含 baseUrl / modelName / protocol / capabilities / orderIndex / apiKey。`EndpointRegistry` 按 endpointId 懒创建并缓存 `LlmClient`，`orderIndex=0` 的端点为主模型。`LlmProtocol`（OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE）取代旧 `LlmProvider` 作为协议分派依据，Gemini 走 OpenAI 兼容端点。旧 `LlmProvider` 枚举保留作引导流程的「预设包」（默认 baseUrl / model / protocol 映射），不再作为运行时寻址 key。端点 CRUD / API Key 变更通过 `ConfigRepository._endpointChanges` SharedFlow 通知 `EndpointRegistry` 自动失效缓存。

### 档位联动

AiActivityLevel 枚举含 rpmLimit / dailyPostsPerAccount / personaUpdateBatchSize / personaUpdatePeriodDays 四个参数。档位切换后通过 SharedFlow 通知调度器，以 REPLACE 策略重排 PersonaUpdateWorker，使新周期立即生效。

### 人设漂移防护

PersonaUpdatePromptBuilder.shouldRollback 使用字符级 Jaccard 相似度校验 LLM 输出与原人设的相似度，低于阈值时回退，防止人设突变。

## 数据库版本

当前 Room 数据库版本为 8，schema JSON 导出至 `core-data/schemas/`（当前目录仅含 1.json~5.json，v6/v7/v8 待在有 Android SDK 的环境中重新生成，详见 #295）。每次 schema 变更需递增版本号并编写 Migration。
