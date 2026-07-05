# LLM 驱动的高拟真社交平台 (Android) Spec

## Why
当前主流社交应用（X / Twitter）的内容生态由真人驱动，缺少一个可以让用户在私密、可控环境下体验完整社交互动、并由 LLM 实时生成高质量内容与互动的开源平台。本项目旨在填补这一空白，交付一个视觉与功能高度还原 X / Twitter、由 LLM 驱动虚拟账号生态、面向 Android 的开源应用（APK）。

## What Changes
- 新建一个原生 Android 应用项目（Kotlin + Jetpack Compose），整体采用 Apple 风格设计语言
- 实现三页主架构：首页信息流、时间线（朋友圈式布局展示图片）、我的；底部使用 iOS 26 风格磨砂玻璃 Tab 栏，右侧附独立等高发布按钮
- 实现相机式发布界面：顶部胶囊形横向 Tab 切换"相机 / 编辑器"，底部实时预览已拍照片，点击照片缩入首页信息流，可在图片顶部输入文本
- 集成多 LLM 提供商（OpenAI / Anthropic / Google Gemini / 兼容 OpenAI 协议的自定义端点），首次启动引导用户配置 API Key
- 内置至少 200 个设定完善且互不相同的虚拟账号，每个账号包含固定字段（人生观、价值观、语言风格、活跃时间段）与 AI 动态维护字段（人生经历、工作信息、关系网络）
- 实现 AI 调度系统：依据账号活跃时间段规则集，在时间窗内随机触发新推文生成（如某时段内随机 2 条）；AI 账号之间及与用户之间进行点赞、评论、转发、关注互动
- 虚拟账号发布的配图使用本地内置图库（按主题分类的静态图片集），不依赖 AI 图像生成
- 实现高度拟真：账号头像、显示名、bio、发布时间分布、互动延迟、错别字 / 口癖 / 表情习惯均符合人设
- 完成本地持久化（Room 数据库）、离线浏览已加载内容、网络异常降级
- 输出可安装的 release APK

## Impact
- Affected specs: 全新项目，无既有 spec 受影响
- Affected code: 新建 Android 工程，包含但不限于
  - `app/` 主模块（UI、ViewModel、Repository、DI）
  - `core-designsystem/` Apple 风格设计系统
  - `core-data/` Room 数据库、Repository
  - `core-llm/` 多 LLM 提供商客户端、Prompt 工程
  - `core-scheduler/` AI 账号调度系统（WorkManager）
  - `feature-feed/` 首页信息流
  - `feature-timeline/` 朋友圈式图片时间线
  - `feature-profile/` 我的页面
  - `feature-publish/` 相机式发布流程
  - `feature-onboarding/` API Key 配置引导
  - `assets/personas/` 200+ 虚拟人设种子数据
  - `assets/gallery/` 按主题分类的静态配图库

## 技术决策（Technology Decisions）

### TD-1: 语言与构建
- Kotlin 2.0+，JVM 17
- Gradle 8.7+，Kotlin DSL，版本目录 `gradle/libs.versions.toml`
- minSdk 26（覆盖 Android 8.0+，约 95%+ 设备），targetSdk 34，compileSdk 34
- 模块化：10 个 Gradle 模块（详见 Impact），仅 `app` 模块包含 `application` 插件，其余为 `library`

### TD-2: UI 框架
- Jetpack Compose（BOM 2024.06+），全面 Compose Navigation
- 单 Activity 架构，`MainActivity` 持有 `NavHost`
- 状态管理：`StateFlow` + `ViewModel`（Lifecycle 2.7+）
- 不使用 XML 与 Compose 混排

### TD-3: DI
- Hilt 2.51+（基于 Dagger），`Application` 类 `@HiltAndroidApp`
- 各模块通过 `@Module` + `@InstallIn(SingletonComponent)` 暴露绑定

### TD-4: 数据持久化
- Room 2.6+，schema 版本管理，导出 schema JSON 至 `schemas/`
- DataStore Preferences 持久化用户配置（API Key、引导标记、默认 LLM 提供商）
- 图片缓存：Coil 2.6+（内存 + 磁盘 LRU），虚拟账号头像与配图落盘至 `filesDir/media/`

### TD-5: 网络
- Retrofit 2.11+ + OkHttp 4.12+，每个 LLM 提供商独立 Retrofit 实例
- SSE 流式：OkHttp `EventSource` + 自实现解析（避免依赖过大）
- 全局超时：连接 15s，读取 60s（LLM 长响应），写入 30s
- 拦截器链：Auth → Logging（仅 debug）→ Retry（指数退避，最多 3 次）

### TD-6: AI 调度
- WorkManager 2.9+，使用 `OneTimeWorkRequest` + `setInitialDelay` 实现精确触发
- 前台服务 `SchedulerForegroundService`（Android 14+ `dataSync` 类型）保活调度
- 调度规则存储于 Room，运行时缓存于内存 `ConcurrentHashMap` 减少查询
- 失败重试：`BackoffPolicy.EXPONENTIAL`，10s / 30s / 90s，最多 3 次

### TD-7: 相机
- CameraX 1.3+，`PreviewView` 渲染取景框
- 比例切换：`aspectRatio` 参数动态调整 `ImageCapture`
- 拍照结果：JPEG 质量 90，落盘至 `cacheDir/capture/`

### TD-8: 图片加载
- Coil 2.6+，统一加载本地与网络图片
- 大图查看：`ZoomableImage`（自实现双指缩放 + 双击缩放，避免引入额外依赖）

## ADDED Requirements

### Requirement: 项目工程与构建
系统 SHALL 使用 Kotlin + Jetpack Compose 构建原生 Android 应用，最低 SDK 26，目标 SDK 34，使用 Gradle Kotlin DSL 与版本目录（libs.versions.toml）管理依赖，支持通过 `./gradlew assembleRelease` 产出可签名 APK。

#### Scenario: 构建产出 APK
- **WHEN** 开发者执行 `./gradlew assembleRelease`
- **THEN** 在 `app/build/outputs/apk/release/` 下生成可安装的 APK 文件

#### Scenario: 最小 SDK 兼容
- **WHEN** 用户在 Android 8.0 (API 26) 及以上设备安装
- **THEN** 应用可正常启动并运行

#### Scenario: 模块独立性
- **WHEN** 编译任一 feature 模块
- **THEN** 不应直接依赖其他 feature 模块，必须通过 `core-*` 模块间接通信

### Requirement: Apple 风格设计系统
系统 SHALL 提供统一的 Apple 风格设计系统，包含 SF 风格字体回退、系统色彩（含深色模式）、磨砂玻璃（BlurEffect）效果组件、圆角与阴影规范，确保与 iOS 26 视觉一致。

#### Scenario: 深色模式
- **WHEN** 用户切换系统深色模式
- **THEN** 应用所有界面自动适配深色配色

#### Scenario: 磨砂玻璃底栏
- **WHEN** 用户在主界面滚动信息流
- **THEN** 底部 Tab 栏呈现半透明磨砂玻璃效果，可透出后方内容

#### Scenario: 低版本磨砂玻璃降级
- **WHEN** 设备 Android 12 (API 31) 以下
- **THEN** RenderEffect 不可用，降级为半透明纯色背景（保留视觉一致性）

### Requirement: 三页主架构与底部导航
系统 SHALL 提供首页（信息流）、时间线（朋友圈式图片）、我的三个主页面，通过底部 iOS 26 风格磨砂玻璃 Tab 栏切换；Tab 栏右侧附一个与 Tab 等高的独立发布按钮。

#### Scenario: Tab 切换
- **WHEN** 用户点击底部 Tab 项
- **THEN** 切换到对应页面并保持 Tab 栏可见

#### Scenario: 发布入口
- **WHEN** 用户点击右侧发布按钮
- **THEN** 进入相机式发布界面

#### Scenario: Tab 状态保持
- **WHEN** 用户从发布界面返回首页
- **THEN** 信息流滚动位置与 Tab 选中状态保持不变

### Requirement: 首页信息流
系统 SHALL 提供与 X / Twitter 一致的信息流，展示虚拟账号与用户发布的推文（文本 + 图片），支持下拉刷新、上拉加载、点赞、评论、转发、收藏。

#### Scenario: 浏览信息流
- **WHEN** 用户进入首页
- **THEN** 按时间倒序展示推文卡片，包含头像、显示名、用户名、发布时间、文本、图片、互动按钮

#### Scenario: 下拉刷新
- **WHEN** 用户下拉首页顶部
- **THEN** 触发刷新并加载新推文

#### Scenario: 上拉加载
- **WHEN** 用户滚动至信息流底部
- **THEN** 自动加载下一页（每页 20 条），无更多时显示"已加载全部"

#### Scenario: 推文内容长度限制
- **WHEN** 推文文本超过 280 字符
- **THEN** 卡片默认折叠，显示"展开全文"按钮

### Requirement: 时间线页面（朋友圈式）
系统 SHALL 提供类似微信朋友圈布局的时间线页面，按日期分组展示用户发送过的所有图片推文。

#### Scenario: 浏览图片时间线
- **WHEN** 用户进入时间线页
- **THEN** 按日期分组展示图片，支持点击查看大图

#### Scenario: 空状态
- **WHEN** 用户从未发布带图推文
- **THEN** 展示空状态插画与引导文案"去发布第一条带图推文"

### Requirement: 我的页面
系统 SHALL 提供我的页面，展示当前用户头像、显示名、bio、发布数、关注 / 粉丝数、发布的推文列表、设置入口（API Key 管理、人设管理、清除缓存）。

#### Scenario: 查看个人资料
- **WHEN** 用户进入我的页面
- **THEN** 展示个人资料卡片与推文列表

#### Scenario: 修改 API Key
- **WHEN** 用户在设置中点击 API Key 管理
- **THEN** 可重新配置各 LLM 提供商的 Key

#### Scenario: 编辑个人资料
- **WHEN** 用户点击编辑资料
- **THEN** 可修改头像（从相册选择）、显示名、bio

### Requirement: 相机式发布界面
系统 SHALL 提供相机式发布界面，顶部胶囊形横向 Tab 切换"相机 / 编辑器"；相机模式下用户可选择图片比例（1:1 / 4:3 / 16:9），底部实时预览已拍照片；点击预览照片后照片缩小飞入首页信息流，用户可在图片顶部输入文本后发布。

#### Scenario: 选择比例
- **WHEN** 用户在相机模式选择 16:9 比例
- **THEN** 取景框与预览按 16:9 显示

#### Scenario: 发布带图推文
- **WHEN** 用户点击预览照片并输入文本后点击发布
- **THEN** 推文进入首页信息流顶部

#### Scenario: 编辑器模式
- **WHEN** 用户切换到编辑器 Tab
- **THEN** 可从相册选择图片并进行裁剪 / 滤镜编辑

#### Scenario: 相机权限缺失
- **WHEN** 用户未授予相机权限
- **THEN** 显示权限请求卡片，引导用户前往系统设置授权

#### Scenario: 缩小飞入动画
- **WHEN** 用户点击底部预览照片
- **THEN** 照片以 SharedElement 过渡动画缩小并飞入首页信息流顶部位置

### Requirement: 多 LLM 提供商集成
系统 SHALL 集成 OpenAI、Anthropic、Google Gemini 及兼容 OpenAI 协议的自定义端点，统一抽象为 `LlmClient` 接口，支持流式与非流式调用。

#### Scenario: 配置 API Key
- **WHEN** 用户首次启动应用
- **THEN** 引导用户配置至少一个 LLM 提供商的 API Key 与 Base URL

#### Scenario: 切换提供商
- **WHEN** 用户在设置中切换默认 LLM 提供商
- **THEN** 后续所有 AI 调用使用新提供商

#### Scenario: 流式响应
- **WHEN** 调用 `chat` 流式方法
- **THEN** 返回 `Flow<String>`，逐 token 推送增量文本

#### Scenario: API Key 加密存储
- **WHEN** 用户保存 API Key
- **THEN** 使用 Android Keystore + EncryptedSharedPreferences 加密存储，不以明文落盘

### Requirement: 虚拟账号系统（200+ 人设）
系统 SHALL 内置至少 200 个设定完善且互不相同的虚拟账号，每个账号包含：
- 固定字段：人生观、价值观、语言风格、活跃时间段
- AI 动态维护字段：人生经历、工作信息、关系网络、最近情绪状态

#### Scenario: 人设唯一性
- **WHEN** 应用初始化加载人设种子
- **THEN** 200+ 账号的人生观、价值观、语言风格、活跃时间段互不相同

#### Scenario: 动态字段更新
- **WHEN** AI 调度系统运行
- **THEN** 周期性更新账号的动态字段（人生经历、工作信息等）

#### Scenario: 人设字段数据结构
- **WHEN** 加载人设
- **THEN** 每条人设包含：
  - `id`（唯一标识，UUID v4）
  - `displayName`（显示名，如"林夏"）
  - `username`（@用户名，唯一）
  - `avatarSeed`（确定性头像种子）
  - `bio`（个人简介，≤160 字符）
  - `worldview`（人生观，2-3 句）
  - `values`（价值观，2-3 句）
  - `languageStyle`（语言风格描述：正式/口语/幽默/犀利/温和）
  - `catchphrase`（口癖，可为空）
  - `emojiPreference`（常用 emoji 列表）
  - `typoRate`（错别字概率 0.0-0.1）
  - `activeWindows`（活跃时间段数组，每小时槽位 0-23 标记是否活跃）
  - `profession`（职业）
  - `ageRange`（年龄段：18-24/25-34/35-44/45+）
  - `culturalBackground`（文化背景）

### Requirement: AI 调度与拟真互动
系统 SHALL 依据每个账号的活跃时间段规则集，在时间窗内随机触发新推文生成（默认每窗 2 条），并由 AI 账号对用户与彼此的推文进行点赞、评论、转发、关注互动，互动延迟符合真人节奏。

#### Scenario: 定时发布推文
- **WHEN** 当前时间进入某账号活跃时间段
- **THEN** 在该时间段内随机时刻生成并发送 2 条符合人设的推文

#### Scenario: 拟真互动
- **WHEN** 用户发布一条推文
- **THEN** 在符合人设的延迟后，相关 AI 账号进行点赞 / 评论 / 转发

#### Scenario: 互动延迟分布
- **WHEN** AI 账号收到互动触发
- **THEN** 延迟时间符合对数正态分布：点赞 30s-5min，评论 2-15min，转发 5-30min

#### Scenario: 互动相关性匹配
- **WHEN** 用户发布推文
- **THEN** 从 200+ 账号中按人设兴趣相似度（基于 bio 关键词、职业重合度）选取 3-8 个账号参与互动

#### Scenario: 调度幂等性
- **WHEN** 调度任务被重复触发（如设备重启后 WorkManager 重放）
- **THEN** 通过 `deduplicationKey`（账号 ID + 时间窗 + 推文序号）去重，避免重复生成

### Requirement: 虚拟账号配图（本地图库）
系统 SHALL 为虚拟账号发布的带图推文从本地内置图库中选取配图，图库按主题分类（风景、美食、城市、宠物、运动、艺术等），不依赖任何 AI 图像生成 API。

#### Scenario: 选取配图
- **WHEN** AI 账号发布带图推文
- **THEN** 根据推文主题从对应分类的本地图库中随机选取一张图片作为配图

#### Scenario: 配图去重
- **WHEN** 同一账号近期已使用某图片
- **THEN** 30 天内不重复选取同一张图片（基于账号 + 图片哈希记录）

#### Scenario: 图库容量
- **WHEN** 应用打包
- **THEN** 每个主题分类至少 20 张图片，总计 ≥ 200 张，APK 体积控制在 50MB 以内（图片压缩至 WebP，质量 80）

### Requirement: 本地持久化与离线支持
系统 SHALL 使用 Room 数据库持久化账号、推文、互动、关系网络，支持离线浏览已加载内容，网络异常时降级展示本地数据。

#### Scenario: 离线浏览
- **WHEN** 设备无网络连接
- **THEN** 用户仍可浏览已加载的信息流与时间线

#### Scenario: 数据库迁移
- **WHEN** 应用升级且数据库 schema 变更
- **THEN** 通过 Room `Migration` 类增量迁移，不丢失用户数据

#### Scenario: 数据库索引
- **WHEN** 信息流查询推文
- **THEN** `tweets` 表在 `createdAt`、`authorId` 字段建索引，分页查询 < 50ms

### Requirement: 首次启动引导
系统 SHALL 在首次启动时引导用户配置 LLM API Key（提供商选择、Key 输入、Base URL 可选、连通性测试），配置完成后进入主界面。

#### Scenario: 完成引导
- **WHEN** 用户输入 API Key 并通过连通性测试
- **THEN** 保存配置并进入主界面

#### Scenario: 跳过引导
- **WHEN** 用户选择稍后配置
- **THEN** 进入主界面但 AI 功能不可用，提示用户前往设置配置

#### Scenario: 连通性测试失败
- **WHEN** API Key 无效或网络不通
- **THEN** 显示具体错误（401 / 超时 / DNS 失败）并提供重试按钮

## MODIFIED Requirements
（本项目为全新项目，无既有 Requirement 需修改）

## REMOVED Requirements
（无移除项）

## 实现风险与应对（Implementation Risks & Mitigations）

### RISK-1: LLM 调用成本与配额超限
**问题**: 200+ 账号 × 每窗 2 条推文 + 互动评论，单日 LLM 调用量可能达数百次，易触发提供商 RPM/TPM 限制，且产生高额费用。
**应对**:
- 全局调用速率限制器（令牌桶，默认 30 RPM）
- 单账号每日推文生成上限可配（默认 4 条）
- 评论生成批量化：一次调用生成多条评论（批量 Prompt）
- 失败时指数退避重试，429 响应直接跳过该次调度
- 用户可在设置中调整"AI 活跃度"档位（低 / 中 / 高），控制整体调用频率

### RISK-2: AI 内容质量与人设一致性漂移
**问题**: LLM 在长上下文或多次调用后，可能偏离人设（如温和人设突然输出攻击性内容），或生成不符合中文语境的内容。
**应对**:
- 每次调用都将人设固定字段（人生观/价值观/语言风格）作为 system prompt 注入
- 输出后做关键词过滤（敏感词、攻击性词汇）
- 推文长度硬限制（≤280 字符，超长截断 + 省略号）
- 提供内容审核 hook，便于后续扩展
- 人设动态字段更新时，对比新旧字段差异，差异过大时回退（防 LLM 突变）

### RISK-3: Android 后台调度不可靠
**问题**: 国产 ROM（MIUI/EMUI/ColorOS）激进杀后台，WorkManager 在 Doze 模式下可能延迟数小时甚至不触发，导致 AI 账号"沉默"。
**应对**:
- 前台服务 `SchedulerForegroundService` 常驻（带通知），提高优先级
- 应用启动时检查"积压调度"，补发错过的活跃时间段推文（按时间窗对齐）
- 引导用户加入"电池优化白名单"（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）
- 关键调度使用 `AlarmManager.setExactAndAllowWhileIdle` 作为兜底
- 降级策略：若调度严重延迟，则在用户打开应用时即时生成补发推文（避免长时间无内容）

### RISK-4: SSE 流式解析复杂性与兼容性
**问题**: 各提供商 SSE 协议略有差异（OpenAI `data:` / Anthropic `event:` 多类型 / Gemini 用 HTTP chunked 非 SSE），统一抽象难度大。
**应对**:
- `LlmClient` 接口只暴露 `Flow<String>`，内部各提供商独立实现解析
- OpenAI/Anthropic 用 OkHttp `EventSource`；Gemini 用 `streamGenerateContent` + 手动 JSON 行解析
- 单元测试覆盖各提供商的 mock SSE 响应
- 流式失败时降级为非流式调用（`chatSync`）

### RISK-5: 200+ 人设数据生成与维护
**问题**: 手工编写 200+ 不重复且高质量的人设不现实；纯 LLM 生成可能产生重复或低质量人设。
**应对**:
- 半自动化：先用脚本基于模板生成骨架（职业 × 年龄 × 文化背景矩阵），再用 LLM 批量润色填充
- 唯一性校验：人生观/价值观/语言风格三元组的相似度（embedding cosine）< 0.85
- 人设文件分片存储（`personas_001.json` ~ `personas_010.json`，每片 20 条），避免单文件过大
- 首次启动异步导入（不阻塞 UI），显示进度

### RISK-6: 磨砂玻璃效果在低端机性能问题
**问题**: `RenderEffect.createBlurEffect` 在中低端 GPU 上滚动时帧率骤降。
**应对**:
- 检测设备性能（`ActivityManager.isLowRamDevice`、GPU 信息），低端机降级为纯色半透明
- 模糊半径动态调整（高端 20dp，中端 10dp，低端 0dp）
- 滚动时暂停模糊渲染（`compositionLocal` 监听滚动状态）
- 预渲染模糊背景至 Bitmap，避免实时计算

### RISK-7: 相机预览与比例切换的兼容性
**问题**: 不同设备 CameraX 支持的 `aspectRatio` 不一致，强制比例可能导致预览拉伸或黑边。
**应对**:
- 使用 `Preview.Builder.setTargetAspectRatio()` 并查询设备支持的组合
- 拍照时按目标比例裁剪 JPEG（使用 `Bitmap.crop`）
- 在不支持的比例上 fallback 至最近支持比例并提示
- CameraX `CameraInfo` 检测并记录设备能力

### RISK-8: SharedElement 过渡动画在 Compose 中实现复杂
**问题**: "照片缩小飞入信息流"的动画，Compose 早期 `SharedElement` API 不稳定。
**应对**:
- 使用 `Modifier.graphicsLayer` + `AnimatedContent` 手动实现缩放 + 位移
- 或使用 Accompanist `SharedElement` 实验性 API（评估稳定性）
- 动画时长 400ms，使用 `FastOutSlowInEasing`
- 若实现成本过高，降级为简单的 fade + scale 过渡

### RISK-9: Room 数据库 schema 演进
**问题**: 项目仍在迭代，schema 频繁变更，迁移代码易遗漏。
**应对**:
- 开发期启用 `fallbackToDestructiveMigration`，发布版必须写显式 Migration
- 每次 schema 变更导出 JSON 至 `schemas/`，PR review 必须包含 schema diff
- 关键表（tweets/accounts）字段变更必须提供默认值

### RISK-10: APK 体积膨胀
**问题**: 200+ 人设 JSON + 200 张配图 + CameraX + Compose + 多 LLM SDK，APK 易超 100MB。
**应对**:
- 配图使用 WebP（质量 80），单张 < 100KB
- 人设 JSON 压缩存储，启动时解压
- 启用 R8 全面混淆 + resource shrinking
- 按需拆 ABI（`arm64-v8a` / `armeabi-v7a` / `x86_64` 各自 release APK），或使用 App Bundle
- 目标 release APK ≤ 50MB

### RISK-11: 用户隐私与 API Key 安全
**问题**: API Key 是敏感凭证，明文存储有泄露风险（root 设备、备份导出）。
**应对**:
- 使用 `EncryptedSharedPreferences`（基于 Android Keystore）
- `android:allowBackup="false"` 防止备份导出 Key
- 日志中 Key 脱敏（仅显示前 4 + 后 4 字符）
- 不将 Key 写入任何外部存储

### RISK-12: AI 生成内容的法律与伦理风险
**问题**: 虚拟账号可能生成侵权、虚假、误导性内容，且未标注"AI 生成"可能违反监管要求。
**应对**:
- 每条 AI 推文在数据库标记 `isAiGenerated=true`
- UI 上对 AI 推文添加细微标识（如头像角落小点，或在详情页标注）
- 内容过滤：敏感词库 + LLM 自审核 prompt（"输出前检查是否包含暴力/仇恨/色情"）
- 应用首次启动展示免责声明，说明内容为 AI 生成
- 不允许 AI 账号生成涉及真实人物姓名的虚假陈述

### RISK-13: 多 LLM 提供商响应格式不一致
**问题**: OpenAI/Anthropic/Gemini 的 JSON 输出能力不同（Gemini 早期不支持严格 JSON mode），导致推文结构化解析失败。
**应对**:
- Prompt 中要求输出 JSON，并用正则 + 宽松解析提取字段
- 优先使用支持 JSON mode 的能力（OpenAI `response_format`）
- 解析失败时降级为纯文本推文（不强制带图）
- 各提供商独立测试集覆盖

### RISK-14: 用户首次体验冷启动无内容
**问题**: 新用户配置 API Key 后，若 AI 调度尚未触发，信息流为空，体验差。
**应对**:
- 引导完成后立即触发"冷启动内容填充"：选取 20 个高活跃账号，即时生成各 1-2 条推文
- 同时展示预置的"历史推文"（人设种子自带 5-10 条历史推文，营造账号"早已存在"的感觉）
- 历史推文时间戳分布在用户首次启动前的 1-30 天

### RISK-15: 调试与可观测性
**问题**: AI 调度后台运行，出问题难以排查。
**应对**:
- 调度事件全部写入 `scheduler_log` 表（时间、账号、动作、结果、耗时）
- 我的页面 → 设置 → 开发者选项（连点 7 次）：查看调度日志、手动触发调度、查看 LLM 调用统计
- Crash 上报：集成 ACRA 或类似库（默认关闭，用户可开启）
