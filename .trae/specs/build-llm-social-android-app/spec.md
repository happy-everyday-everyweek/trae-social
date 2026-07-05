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

---

## 实现问题深度分析（Implementation Problem Deep Analysis）

> 以下问题基于已实现代码（Tasks 1-14 完成）的实际审计，按严重度分级。每条标注定位（文件/模块）、问题、影响、修复策略。RISK 章节描述"预期风险"，本章节记录"实际踩坑"。修复时需走 git 分支流程（用户规则）。

### 阻断级问题（P0 - 阻塞核心链路，必须修复）

#### IMPL-1: PersonaSeeder 从未被调用，220 个账号与历史推文未入库
- **定位**: `core-data/src/main/java/com/trae/social/core/data/seed/PersonaSeeder.kt`（定义 `seedIfNeeded()`）+ `app/src/main/java/com/trae/social/app/SocialApp.kt`（onCreate 未调用）+ `feature-onboarding` 的 `DefaultColdStartFiller`
- **问题**: `PersonaSeeder.seedIfNeeded()` Flow 全局搜索仅在自身定义处出现；`SocialApp.onCreate` 只调用 `SchedulerInitializer.initialize`，未触发 seeder；`DefaultColdStartFiller.triggerInitialFill()` 为空实现。结果：数据库 `accounts` / `tweets` 表为空，AI 调度无账号可调度，信息流无内容。
- **影响**: 用户完成引导后进入主界面看到空白信息流；RISK-14 描述的"冷启动内容填充"完全失效；后续所有 AI 调度链路因无账号而 short-circuit。
- **修复策略**:
  1. 在 `SocialApp.onCreate` 末尾或 `MainActivity.onCreate` 中 `viewModelScope.launch { personaSeeder.seedIfNeeded().collect { ... } }` 触发首次种子导入
  2. 将 `ColdStartFiller` 真实实现移至 app 模块（用 `@TestInstallIn` 替换 `OnboardingModule` 的默认绑定，或将 `OnboardingModule` 改为 `@Binds` + qualifier 区分默认与真实实现，避免 Hilt 重复绑定）
  3. 真实 `ColdStartFiller` 内部 `WorkManager.enqueue` 20 个高活跃账号的 `TweetGenerationWorker`

#### IMPL-2: feature-profile 整个模块仅占位实现
- **定位**: `feature-profile/src/main/java/com/trae/social/profile/ProfileScreen.kt`（31 行，仅 `Text("我的（待实现）")`）
- **问题**: spec Task 13 的 4 个子任务（个人资料卡片、推文/媒体/喜欢 Tab、设置入口、关注/粉丝列表）全部未实现；`ProfileViewModel` / `DevOptionsScreen` / `ApiKeyManagementScreen` / `FollowListScreen` 文件不存在。
- **影响**: 主框架"我的" Tab 无任何功能；RISK-15 的开发者选项无法查看调度日志（RISK-15 应对失效）；引导跳过后的"前往设置"banner 无落地页。
- **修复策略**: 完整实现 Task 13 四个子任务（详见 tasks.md Task 13 实现注意事项）；`ApiKeyManagementScreen` 可提取 `KeyInputScreen` + `ConnectionTestScreen` 为可复用 Composable；`DevOptionsScreen` 用 `rememberSaveable` 持久化 7 次连点解锁状态。

#### IMPL-3: InteractionWorker 因 authorId="user-self" 短路，用户推文无 AI 互动
- **定位**: `core-scheduler/src/main/java/com/trae/social/core/scheduler/work/InteractionWorker.kt`（L74-78 `accountRepository.getById(tweet.authorId)` 返回 null 时 `skipped_no_author`）+ `feature-publish/src/main/java/com/trae/social/publish/PublishViewModel.kt`（L189 `AUTHOR_SELF = "user-self"`）
- **问题**: 用户发布的推文 `authorId = "user-self"`，DB 中无此 AccountEntity，`InteractionWorker` 第一行查作者返回 null 直接 success 退出。整条"用户发布 → AI 互动"链路被静默短路。
- **影响**: spec Task 15.1 端到端验证"用户发布 → AI 互动"完全不通；用户推文收不到任何 AI 点赞/评论/转发，拟真体验崩塌。
- **修复策略**:
  1. 在 `PersonaSeeder` 首次导入时插入 `id="user-self"` 的 AccountEntity（`isVirtual=false`，displayName="我"）
  2. 或在 `InteractionWorker` 内对 `authorId == "user-self"` 走特殊路径，跳过作者查询直接选 3-8 个虚拟账号评论
  3. 降级路径 `accountId = "ai-fallback"` 也应改为从虚拟账号池随机选一个真实 ID

#### IMPL-4: 跨日补发 deduplicationKey 冲突，补发失效且浪费配额
- **定位**: `core-scheduler/src/main/java/com/trae/social/core/scheduler/rule/ScheduleRuleResolver.kt`（`missedWindows` 返回 `List<TimeWindow>` 仅含 startHour/endHour，无日期）+ `SchedulerInitializer.kt`（L146-163 `windowStartMillis` 始终用 `now.toLocalDate()` 拼装）
- **问题**: `missedWindows` 跨日时返回多个相同 `TimeWindow(9,11)`，调用方用"今天"日期拼装 `windowStart`，导致昨日 9 点与今日 9 点生成相同 `deduplicationKey = "accountId_today9am_0"`。第二个 insert 命中唯一约束被静默吞掉，但 LLM 调用已消耗。
- **影响**: 跨日补发只生效 1 条；每次补发浪费 1 次 LLM 配额；`missedWindows` 的"昨日/今日"语义在 KDoc 中宣称便于识别，实际未传递到调用方。
- **修复策略**: 让 `missedWindows` 返回 `data class MissedWindow(val date: LocalDate, val window: TimeWindow)`；或 `SchedulerInitializer` 按 day 循环遍历窗口，用窗口所在日拼装 `windowStart`。

#### IMPL-5: InteractionWorker 重复入队/重试产生重复互动记录
- **定位**: `core-scheduler/src/main/java/com/trae/social/core/scheduler/SchedulerInitializer.kt`（`enqueueTweetGeneration` / `triggerAiInteraction` 用 `workManager.enqueue` 非 unique）+ `core-data/src/main/java/com/trae/social/core/data/entity/InteractionEntity.kt`（主键随机 UUID，无业务唯一约束）
- **问题**: (1) Worker 入队未用 `enqueueUniqueWork`，重复初始化会并发跑同一 (accountId, windowStart)。(2) `InteractionEntity` 无 `(tweetId, accountId, type)` 唯一索引，重试时新 UUID insertAll 成功，单条用户推文最终累计 6-16 条互动。(3) `selectCommenters` 只读 `getAccounts(page=1)`（前 20 个账号），候选池受限。
- **影响**: 互动数倍增破坏"3-8 个评论者"产品语义；重复 LIKE/COMMENT 让 `updateLikeCount` 多次累加导致计数失真。
- **修复策略**:
  1. `enqueueUniqueWork("tweet_gen_${accountId}_${windowStart}_${seq}", ExistingWorkPolicy.KEEP, ...)`
  2. `InteractionEntity` 增 `@Index(value=["tweetId","accountId","type"], unique=true)`
  3. `selectCommenters` 改用 `getAllVirtualAccounts()` 一次性接口

#### IMPL-6: PendingInteractionWorker 的 markExecuted 与 updateCount 非原子，崩溃窗口丢计数
- **定位**: `core-scheduler/src/main/java/com/trae/social/core/scheduler/work/PendingInteractionWorker.kt`（L83-111 逐条 markExecuted 后统一 updateLikeCount）
- **问题**: 流程"逐条 markExecuted → 累加 delta → 循环结束统一 updateCount"。若在 markExecuted 之后、updateCount 之前崩溃（进程被杀/OOM），下一周期 `getPendingBefore` 不再返回这些 interaction（executedAt 已非空），但推文计数永远没被加上。
- **影响**: likeCount/commentCount/retweetCount 静默丢失，与实际互动记录对不上。
- **修复策略**: 用 Room `@Transaction` 包裹 markExecuted + updateCount；或改为每条 interaction 单独 updateCount + markExecuted 同事务。

#### IMPL-7: RetryInterceptor 返回已关闭的 Response，流式调用崩溃
- **定位**: `core-llm/src/main/java/com/trae/social/llm/interceptor/RetryInterceptor.kt`（L33-37 `response.close()` 后 `return response`）
- **问题**: 达到最大重试次数时先 `response.close()` 再 `return response`。对 `@Streaming` 接口，调用方拿到已关闭 body，读取时抛 `IllegalStateException: closed`，被 `catch` 后降级到 `chatSync`，`chatSync` 同样收到错误 body，最终用户得到空响应。
- **影响**: 429/5xx 持续时所有 LLM 调用静默返回空字符串，无错误信号。
- **修复策略**: 不要 `close()`，直接 `return response` 让调用方处理错误体；或抛 `IOException("retry exhausted with code ${response.code}")`。

#### IMPL-8: RetryInterceptor 与流式调用不兼容，已 emit token 后流中断静默丢失
- **定位**: `core-llm/src/main/java/com/trae/social/llm/interceptor/RetryInterceptor.kt` + `OpenAiClient.kt`（L46-51）/ `AnthropicClient.kt`（L53-58）/ `GeminiClient.kt`（L60-65）
- **问题**: RetryInterceptor 只能重试 `chain.proceed()` 阶段失败。流式响应在 proceed 返回后才开始读 body；读取中的 `IOException`（连接中断、SSE 解析错误）发生在拦截器之外，不会被重试。三个 Client 的 catch 块检查 `if (!emitted)`——已 emit 部分 token 后流中断，`emitted == true`，catch 不做任何降级，flow 静默结束。
- **影响**: 调用方得到截断响应且无错误信号；下游 Worker 以为 LLM 成功返回完整内容，写入残缺推文。
- **修复策略**:
  1. 已 emit 部分内容后流中断时，emit 特殊结束标记或抛 `IOException` 让调用方知晓
  2. 在 `chat()` 级别（非拦截器级别）实现重试，区分"未 emit"（可重试）与"已 emit"（不可重试）

### 功能错误级问题（P1 - 功能与设计不符）

#### IMPL-9: PersonaSeeder 历史推文时间戳计算方向错误
- **定位**: `core-data/src/main/java/com/trae/social/core/data/seed/PersonaSeeder.kt`（L156-157 `tweetTime = now - daysAgo * DAY_MS + withinDayOffset`）
- **问题**: `daysAgo=1` 且 `withinDayOffset` 接近 `DAY_MS` 时，`tweetTime` 接近 `now`（仅差几毫秒），"1 天前"的推文显示为"刚刚发布"。本应为 `now - daysAgo * DAY_MS - withinDayOffset`。
- **影响**: 历史推文时间分布倒置，信息流按 `createdAt DESC` 排序时历史推文浮到最前。
- **修复策略**: 改为减号 `now - daysAgo * DAY_MS - withinDayOffset`，确保落入 `[now-(daysAgo+1)*DAY_MS, now-daysAgo*DAY_MS]`。

#### IMPL-10: EncryptedSharedPreferences 无 Keystore 丢失恢复策略
- **定位**: `core-data/src/main/java/com/trae/social/core/data/di/DataModule.kt`（L80-93 `provideEncryptedSharedPreferences` 无 try-catch）
- **问题**: Android Keystore 损坏/重置（用户改锁屏凭据、刷机保留数据、Keystore 进程崩溃）时抛 `GeneralSecurityException`/`IOException`，Hilt 注入失败导致 app 启动崩溃，且因 `@Singleton` 在 Application 初始化阶段，用户无恢复路径。
- **影响**: Keystore 失效后 app 无法启动，已存 API Key 永久丢失。
- **修复策略**: 捕获异常后 `context.deleteSharedPreferences(SECURE_PREFS_FILE_NAME)` 重建空实例；或 fallback 到非加密 prefs 并 Timber 告警，强制用户重新输入 Key。

#### IMPL-11: 点赞乐观更新 +1 与 DB 已 +1 双计，UI 显示比真实多 1
- **定位**: `feature-feed/src/main/java/com/trae/social/feed/FeedViewModel.kt`（L117-151 `updateLikeCount(+1)` 改 DB）+ `feature-feed/src/main/java/com/trae/social/feed/TweetCard.kt`（L88-90 `displayLikeCount = if (isLiked) tweet.likeCount + 1 else tweet.likeCount`）
- **问题**: 乐观更新流程：(a) 加 `_likedTweetIds` → (b) `updateLikeCount(+1)` 改 DB → (c) Room PagingSource 失效重发，新 `tweet.likeCount` 已含 +1 → (d) TweetCard 又 `+1`，显示比真实多 1。例：原 5 → DB 6 → UI 7。
- **影响**: 点赞数显示与实际不一致；取消点赞后正确（DB 5 → UI 5），但点赞瞬间错。
- **修复策略**: `displayLikeCount = tweet.likeCount`（直接用 DB 值）；或将"是否点赞"作为字段写入 `TweetEntity`。

#### IMPL-12: 用户自己发布的推文显示为"未知用户 @unknown"
- **定位**: `feature-feed/src/main/java/com/trae/social/feed/FeedViewModel.kt`（L239-254 `resolveAuthor` 对 authorId="user-self" 返回 null 时 fallback "未知用户"）+ `feature-publish/.../PublishViewModel.kt`（L189 `AUTHOR_SELF = "user-self"`）
- **问题**: DB 无 id="user-self" 的 AccountEntity，`resolveAuthor` fallback 为 displayName="未知用户"、username="unknown"。
- **影响**: 用户自己发的推文在信息流显示"未知用户 @unknown"，头像由 "user-self" hash 到某 SVG。
- **修复策略**: 在 `PersonaSeeder` 首次导入时插入 id="user-self" 的 AccountEntity（`isVirtual=false`，displayName="我"）；或在 `resolveAuthor` 中对 `authorId == "user-self"` 走特殊分支返回用户配置的资料。

#### IMPL-13: 跳过引导与完成引导持久层不可区分，跳过 banner 未实现
- **定位**: `feature-onboarding/src/main/java/com/trae/social/onboarding/OnboardingViewModel.kt`（L198-200 `skip()` 调 `onSkipped()`）+ `OnboardingNavHost.kt`（L52 `onSkipped = onCompleted`）+ `MainActivity.kt`（L91-99 `onCompleted` 写 `setOnboardingCompleted(true)`）
- **问题**: "跳过"与"完成"都写入 `onboarding_completed=true`，`ConfigRepository` 无 `onboardingSkipped` 字段；`FeedScreen` 顶部无 banner 实现（搜索 `banner`/`skipped` 无匹配）。
- **影响**: 跳过引导后用户在主界面看不到任何"补全配置"提示，AI 功能无法启用却无回流入口。
- **修复策略**:
  1. `ConfigRepository` 增 `onboardingSkipped: Boolean`，`skip` 写 `skipped=true, completed=true`，`saveAndComplete` 只写 `completed=true`
  2. `FeedScreen` 顶部根据 `isOnboardingSkipped` 渲染 banner，点击跳转设置页

#### IMPL-14: AppLlmConfigProvider.runBlocking 在引导连通性测试期间阻塞 Main 线程
- **定位**: `app/src/main/java/com/trae/social/app/di/AppLlmConfigProvider.kt`（L30-46 `runBlocking { configRepository.getModelName(...) }`）+ `core-llm/.../LlmProviderRegistry.kt`（`getClient` 是 `@Synchronized` 同步函数）
- **问题**: 文档声称"runBlocking 仅阻塞 OkHttp 线程"，但忽略主线程调用路径：`OnboardingViewModel.testConnection()`（Main dispatcher）→ `llmProviderRegistry.getClient(provider)`（同步）→ `configProvider.getModel/getBaseUrl`（`runBlocking`）。EncryptedSharedPreferences 首次访问做密钥派生 100ms+，Main 上 runBlocking 等待 IO 导致 ANR 风险。
- **影响**: 引导连通性测试点击后 UI 卡顿；Android 系统可能判定主线程无响应。
- **修复策略**:
  1. `LlmProviderRegistry.getClient` 改为 `suspend fun`，调用方 `OnboardingViewModel` 已在 `viewModelScope.launch` 内可直接 await
  2. 或在 `AppLlmConfigProvider` 内部用 `Mutex` + 缓存：首次 `runBlocking` 读完后缓存到 `volatile var`，后续命中缓存不进 `runBlocking`

#### IMPL-15: publish() 失败仍 emit Published，用户被误导为成功
- **定位**: `feature-publish/src/main/java/com/trae/social/publish/PublishViewModel.kt`（L148-152 `try { insertTweet; triggerAiInteraction } catch { Timber.e } finally { _events.send(PublishEvent.Published) }`）
- **问题**: `finally` 块无条件 emit `Published`，即使 `insertTweet` 抛异常（磁盘满、约束冲突）也触发飞入动画 + `onPublished()` 回调返回首页。
- **影响**: 用户以为发布成功，实际 DB 没写入；信息流无新推文，用户困惑。
- **修复策略**: 改为 `try { insertTweet; triggerAiInteraction; _events.send(Published) } catch { _events.send(Failed) }`，UI 收到 Failed 时显示错误 toast 并保留输入。

#### IMPL-16: 跨时区配额与窗位偏移
- **定位**: `core-scheduler/.../rule/ScheduleRuleResolver.kt`（默认 `ZoneId.systemDefault()`）+ `core-data/.../entity/AccountEntity.kt`（无 timezone 字段）+ `core-scheduler/.../ratelimit/DailyQuotaChecker.kt`（L17, L49-53 `startOfDayMillis` 按设备时区）
- **问题**: `activeWindows` 24 槽位绑定设备系统时区。用户从上海（UTC+8）飞洛杉矶（UTC-8）后，"9-11 上海时间活跃"被重新解读为"9-11 洛杉矶时间活跃"（绝对时刻平移 16 小时）；`DailyQuotaChecker.startOfDayMillis` 按新时区计算"今日 00:00"，可能把昨日推文重新计入今日配额。
- **影响**: 旅行期间推文生成时段错乱、每日配额计数边界漂移。
- **修复策略**: `AccountEntity` 新增 `timezone: String`（如 "Asia/Shanghai"），由人设种子指定，Resolver / DailyQuotaChecker 显式传入该 zone；或全局改用 UTC 计数并在 UI 层做时区换算。

#### IMPL-17: SchedulerForegroundService 用 dataSync 类型在 Android 14+ 有合规风险
- **定位**: `core-scheduler/.../SchedulerForegroundService.kt`（L30-50）+ `app/src/main/AndroidManifest.xml`（L60-63 `foregroundServiceType="dataSync"`）
- **问题**: Android 14 对 `FOREGROUND_SERVICE_TYPE_DATA_SYNC` 引入"6 小时/24 小时"配额，到时系统调用 onDestroy。本服务 onStartCommand 仅 `schedulePendingInteractions()` 后返回 START_STICKY，**服务自身不做任何数据同步**，纯为保活 WorkManager，违反 Google Play 对 dataSync 类型的用途要求。Android 15 进一步收紧。
- **影响**: 6 小时后被系统杀掉；Play 审核可能因"dataSync 类型与实际用途不符"拒绝上架。
- **修复策略**: 改用 `foregroundServiceType=specialUse`（Android 14 新增，需声明 `property` 描述用途）；或彻底去掉常驻前台服务，仅依赖 WorkManager + BOOT_COMPLETED 自启。

#### IMPL-18: BootReceiver 用 Handler.postDelayed 不可靠，开机后调度器可能不启动
- **定位**: `core-scheduler/.../BootReceiver.kt`（L20-43 `Handler.postDelayed(..., 30000)`）+ Manifest 仅声明 `BOOT_COMPLETED`（无 `LOCKED_BOOT_COMPLETED`）
- **问题**: `BroadcastReceiver.onReceive` 返回后系统可随时杀进程。30 秒延迟期间进程被杀（高概率），回调永远不执行。Direct Boot 模式下（用户开机后未解锁）`BOOT_COMPLETED` 不会广播。
- **影响**: 开机后调度器可能不启动，依赖用户手动打开 App 才触发 `SchedulerInitializer.initialize`。
- **修复策略**: 用 `OneTimeWorkRequest` + `setInitialDelay(30, SECONDS)` 通过 WorkManager 启动服务（WorkManager 进程内执行，不受 BroadcastReceiver 生命周期限制）；同时注册 `LOCKED_BOOT_COMPLETED`。

#### IMPL-19: 429 与 RetryInterceptor 退避策略冲突，且其它 Worker 完全未处理 429
- **定位**: `core-scheduler/.../work/TweetGenerationWorker.kt`（L152-160 注释称"429 跳过"但底层 RetryInterceptor 已 HTTP 层重试）+ `RetryInterceptor.kt`（L26-51）+ `InteractionWorker.kt`（L252-260 catch 一律返回 emptyMap）/ `PendingInteractionWorker.kt` / `PersonaUpdateWorker.kt`
- **问题**: TweetGenerationWorker 注释"429 时返回 success 跳过重试"，但底层 `RetryInterceptor` 已在 HTTP 层对 429 做了 `MAX_RETRY_ATTEMPTS` 次指数退避重试，等抛到 Worker 时已耗费若干次请求 + 退避时间；Worker 的 `BackoffPolicy.EXPONENTIAL 10s/30s/90s` 与 RetryInterceptor 的 `base*2^(attempt-1)` 是两层独立退避，行为叠加不可预测。其它 Worker 对 LLM 异常一律 `catch { return emptyMap() }`，不区分 429 与其它错误，429 时返回 success 但内容为空，下个周期再试，形成隐式重试循环。
- **影响**: 429 场景下配额浪费、行为不可预测；其它 Worker 的 429 行为失控。
- **修复策略**: 让 `RetryInterceptor` 对 429 直接抛 `RateLimitedException(retryAfter)` 不再 HTTP 层重试；各 Worker 统一捕获该异常并按 `Retry-After` 头计算 `Result.retry()` 的延迟。

#### IMPL-20: 对数正态分布实现错误，是"对数空间均匀分布"
- **定位**: `core-scheduler/.../work/InteractionWorker.kt`（L210-229）
- **问题**: 注释声称"按对数正态分布生成互动延迟"，实现却是 `random.nextDouble()` 均匀 [0,1) 后 `logMin + sample*(logMax-logMin)` 取指数——这是对数空间均匀采样，概率密度是 1/x，不是对数正态（应 `random.nextGaussian()*std+mean` 后取指数）。`mean`/`std` 两行计算了却从未使用，是死代码。
- **影响**: 互动延迟分布偏离产品预期（LIKE 集中在 30s，COMMENT 集中在 2min），自动化行为模式可被识别。
- **修复策略**: 改为 `val gauss = random.nextGaussian(); val logValue = gauss*std+mean; var raw = Math.exp(logValue).toLong()`，做 min/max 截断；或修正注释为"对数空间均匀分布"。

#### IMPL-21: 互动延迟受 PendingInteractionWorker 15 分钟周期限制，LIKE 不像真人"秒赞"
- **定位**: `core-scheduler/.../work/InteractionWorker.kt`（L210-215 用 InteractionEntity.scheduledAt 表达延迟）+ `PendingInteractionWorker.kt`（PeriodicWorkRequestBuilder 15 分钟周期）
- **问题**: 用 PendingInteraction 表达延迟，但 `PendingInteractionWorker` 是 15 分钟周期。LIKE 设计延迟 30s-5min，实际执行时刻 = scheduledAt + (0~15min)，最坏 LIKE 在 20min 后才执行，可能比 COMMENT 还晚。
- **影响**: 互动触发时刻不可控（最大偏差 15 分钟），LIKE 看起来不像真人"秒赞"。
- **修复策略**: 短延迟（< 5min）的 LIKE/FOLLOW 用 `OneTimeWorkRequest` + `setInitialDelay` 直接调度；COMMENT/RETWEET 仍走 PendingInteraction 表。

### 数据完整性问题（P2）

#### IMPL-22: 所有实体均未声明外键约束
- **定位**: `core-data/.../entity/*.kt`（全部 8 个表）+ schema JSON 确认 `"foreignKeys": []` 均为空
- **问题**: `TweetEntity.authorId`、`InteractionEntity.tweetId`/`accountId`、`FollowRelationEntity.followerId`/`followeeId`、`PersonaDynamicFieldEntity.accountId`、`SchedulerLogEntity.accountId`、`ImageUsageEntity.accountId` 均无 `@ForeignKey`。
- **影响**: 删除账号后产生孤儿推文/互动/关注/日志/配图记录；可写入引用不存在账号的推文；数据完整性无保障。
- **修复策略**: 添加 `@ForeignKey(entity=AccountEntity::class, parentColumns=["id"], childColumns=["authorId"], onDelete=CASCADE)`；Room 默认启用 `PRAGMA foreign_keys=ON`。

#### IMPL-23: fallbackToDestructiveMigration 在 release 版的隐患
- **定位**: `core-data/.../di/DataModule.kt`（L41 `.fallbackToDestructiveMigration()`）+ `core-data/build.gradle.kts`（L19-27 release 未差异化处理）
- **问题**: 无参数版本在任何 schema 版本变更时直接 DROP 并重建所有表。注释标注"发布版须替换为显式 Migration"但 release buildType 未做处理。
- **影响**: 后续新增字段/修改表结构时，用户本地推文/互动/关注关系/配图记录/调度日志全部丢失。
- **修复策略**: 改为 `.fallbackToDestructiveMigrationOnDowngrade()`；升级路径提供显式 `Migration` 对象；或在 release buildType 中通过构建变量切换策略。

#### IMPL-24: PersonaSeeder 缺少事务包装，幂等性有缺陷
- **定位**: `core-data/.../seed/PersonaSeeder.kt`（L95-113 每文件 `accountDao.upsertAll` 后 `tweetDao.insertAll`，不在同一事务）
- **问题**: 首次启动若 app 被杀或崩溃在某文件 accounts 写入后、tweets 写入前，该批账号无历史推文；幂等检查 `accountDao.count() > 0` 在下次启动时直接跳过，永久留下不完整数据，UI 却显示 `isComplete=true`。
- **影响**: 数据不完整且无法自愈。
- **修复策略**: 用 `db.withTransaction { }` 包裹每个文件的 accounts + tweets 写入；幂等检查同时校验 tweets 数量。

#### IMPL-25: AccountRepository.updateDynamicFields 双写无事务
- **定位**: `core-data/.../repository/AccountRepository.kt`（L42-58 依次调 `upsertDynamicFields` 与 `updateAccountDynamicSummary`）
- **问题**: 两次写入之间若崩溃，`PersonaDynamicFieldEntity` 已更新但 `AccountEntity` 的 denormalized 摘要字段陈旧。
- **影响**: 人设详情页与列表页显示不一致。
- **修复策略**: 在 `AccountDao` 中新增 `@Transaction` 方法包裹两次写入。

### 健壮性与可观测性问题（P3）

#### IMPL-26: core-llm RateLimiter 硬编码 30 RPM，与 AiActivityLevel 解耦
- **定位**: `core-llm/.../di/LlmModule.kt`（L53 `provideRateLimiter` 硬编码 `LlmHttp.DEFAULT_RPM`）+ `core-data/.../config/LlmConfig.kt`（L26-29 `AiActivityLevel.rpmLimit`）
- **问题**: `AiActivityLevel` 定义了 `rpmLimit`（LOW=10, MEDIUM=30, HIGH=60），但仅在 `core-scheduler/SchedulerRateLimiter` 中使用，core-llm 的 HTTP 层 RateLimiter 不读取它。用户设 HIGH（60 RPM）时 HTTP 层仍限 30 RPM，无法达到预期吞吐。
- **影响**: 档位切换对 HTTP 吞吐无影响，HIGH 档位名不副实。
- **修复策略**: 让 `core-llm/RateLimiter` 支持 `updateMaxTokens(n)`；或在 `LlmProviderRegistry.getClient()` 前根据 `AiActivityLevel` 校准；或统一为一层限流移除 HTTP 层 `RateLimitInterceptor`。

#### IMPL-27: AuthInterceptor 缺少 API Key 时静默放行，无错误反馈
- **定位**: `core-llm/.../interceptor/AuthInterceptor.kt`（L32-34 `if (key.isNullOrBlank()) return chain.proceed(builder.build())`）
- **问题**: 未配置 Key 时以无认证头发出请求，返回 401 后流式客户端按 SSE 解析失败，catch 中 `chatSync` 降级也收到 401，`runCatching` 吞掉异常，最终 `emit("")`。
- **影响**: 未配置 API Key 时所有 LLM 调用静默返回空字符串，`ping()` 返回 false 但无法区分"网络问题"还是"Key 未配置"。
- **修复策略**: 缺少 Key 时直接抛 `IllegalStateException("API key not configured for $provider")` 或返回带 401 状态码的 Response。

#### IMPL-28: AnthropicClient 忽略 SSE error 事件
- **定位**: `core-llm/.../anthropic/AnthropicClient.kt`（L41-50 仅处理 `message_stop` 与 `content_block_delta`，其余 continue）
- **问题**: Anthropic 流式 API 出错时发送 `event: error` + `data: {"type":"error","error":{"type":"overloaded_error","message":"..."}}`，客户端静默忽略，循环正常退出，调用方得到不完整响应且无错误信号。
- **影响**: quota 超限/内容被拦截/服务过载时静默失败。
- **修复策略**: 检测 `event.type == "error"` 时提取 `error.message` 抛 `IOException` 或触发 `chatSync` 降级（若未 emit）。

#### IMPL-29: ContentFilter 敏感词子串匹配过宽，误伤合法讨论
- **定位**: `core-llm/.../prompt/ContentFilter.kt`（L50-53 `lower.contains(it.lowercase())`）
- **问题**: "诈骗"误匹配"反诈骗""防诈骗"；"勒索"误匹配"反勒索"；"走私"误匹配"反走私"；"安乐死"本身是合法伦理讨论话题。
- **影响**: AI 生成的合法推文被误判为敏感并打码，内容质量下降。
- **修复策略**: 对可通过上下文合法使用的词改用更精确匹配（排除"反/防/打击"前缀）；词表外置为可热更新配置；用 Aho-Corasick 算法替代 O(n*m) 扫描。

#### IMPL-30: SchedulerRateLimiter 并发 reconfigure 会互相覆盖，档位切换丢令牌
- **定位**: `core-scheduler/.../ratelimit/SchedulerRateLimiter.kt`（L16-65 `reconfigure` 用 `@Synchronized` 替换整个 `current` 实例）+ `TweetGenerationWorker.kt`（L90-92 每个 Worker 开头调 `reconfigure`）
- **问题**: 多 Worker 并发 + 档位切换时，HIGH Worker 可能在 LOW reconfigure 后读到 10 RPM 限流器被节流；`reconfigure` 丢弃旧桶剩余令牌，频繁切换浪费令牌。
- **影响**: 多 Worker 并发 + 档位切换时实际速率与预期不符。
- **修复策略**: 不在 Worker 内 reconfigure；改为监听 `ConfigRepository` 的 flow 在单点响应档位变化；或 `reconfigure` 时保留旧桶可用令牌数（按比例折算）。

#### IMPL-31: RateLimiter.acquire 用 50ms 轮询忙等，高并发下性能差
- **定位**: `core-llm/.../ratelimit/RateLimiter.kt`（L31-36 `while (!tryAcquire()) delay(50ms)`）+ `refillLocked`（L57-68 不处理时钟回拨）
- **问题**: 30 RPM 桶空时最坏等 60 秒，期间每 50ms 醒来一次抢锁（1200 次锁竞争）。时钟回拨时 `elapsed` 为负，令牌永不补充。
- **影响**: 高并发下 CPU 与电量浪费；时钟回拨导致限流器永久不补充令牌。
- **修复策略**: `acquire()` 计算到下一令牌的精确时间 `delay(精确时间)`；`refillLocked()` 中若 `elapsed<0` 重置 `lastRefillTimestamp=now`。

#### IMPL-32: 拦截器链顺序导致重试无日志，可观测性低
- **定位**: `core-llm/.../di/LlmModule.kt`（L64-67 顺序 Auth→Logging→RateLimit→Retry）
- **问题**: RetryInterceptor 的 `chain.proceed()` 不重新经过 Auth/Logging/RateLimit。Logging 只记录首次请求，重试尝试不记日志。
- **影响**: 重试不可观测，排查问题困难。
- **修复策略**: 在 RetryInterceptor 内部用 `Timber.d` 记录每次重试的 attempt 编号、状态码、退避时长。

### UI/UX 与性能问题（P4）

#### IMPL-33: GlassBlurContainer 用 Modifier.blur 不会模糊背后内容
- **定位**: `core-designsystem/.../components/GlassBlurContainer.kt`（L109-117 `Modifier.blur(effectiveRadius).background(glassTint)`）+ `app/.../ui/SocialBottomBar.kt`
- **问题**: `Modifier.blur` 只模糊自身子内容（Tab 项），不会模糊底栏"背后"的信息流。结果是半透明着色条，不是真正的 iOS 毛玻璃效果。`LocalIsScrolling` 定义了但全局无任何 `provideIsScrolling` 调用方，"滚动时半径减半"优化从未生效。
- **影响**: 视觉效果与 spec "iOS 26 风格磨砂玻璃"有差距；滚动性能优化未启用。
- **修复策略**:
  1. API 31+ 用 `Modifier.graphicsLayer { renderEffect = RenderEffect.createBlurEffect(...).asComposeRenderEffect() }`
  2. 在 feed/timeline 的 `LazyColumn` 状态上派生 `isScrollInProgress`，外层用 `provideIsScrolling(isScrollInProgress) { ... }` 包裹整个 Scaffold

#### IMPL-34: FullScreenImage 缩放=1 时 pager 翻页手势冲突，放大后无平移边界
- **定位**: `feature-timeline/.../FullScreenImage.kt`（L60-215 `Box` 同时挂 `detectTapGestures` 与 `transformable`）+ `feature-feed/.../FullScreenImage.kt`
- **问题**: (1) `transformable` 在 `scale==1f` 时仍消费单指拖拽，与 `HorizontalPager` 横向翻页竞争，未缩放时左右滑动翻页不灵敏。(2) `offset = offset + panChange` 无边界限制，图片可被拖出屏幕外无法回弹。(3) `if (items.isEmpty()) { onDismiss(); return }` 在组合期间调用状态变更，违反 Compose"组合期间无副作用"原则。
- **影响**: 缩放=1 时 pager 翻页体验差；放大后图片可能拖丢；空列表场景崩风险。
- **修复策略**: 用 `Modifier.pointerInput(scale) { detectTransformGestures(...) }` 自行处理，根据 `scale==1f` 决定是否消费拖拽；平移时按 `scale*imageSize-viewportSize` 计算 clamp；空列表 early-return 改为 `LaunchedEffect(items.isEmpty()) { if (it) onDismiss() }`。

#### IMPL-35: CameraX 1:1 比例只是透明遮罩，未真正裁剪
- **定位**: `feature-publish/.../CameraModeContent.kt`（L148-156 `CaptureRatio.SQUARE` 映射到 `RATIO_4_3`，UI 叠加 `Box(Modifier.fillMaxWidth().aspectRatio(1f))` 透明遮罩）
- **问题**: 遮罩无 background，用户视觉上看不到裁切边界；拍照落盘的 JPEG 仍然是 4:3，`mediaPath` 直接保存原始 4:3 图。
- **影响**: 1:1 拍照体验名不副实。
- **修复策略**: 遮罩加非透明黑边背景 + 落盘后用 `Bitmap` 中心裁剪为正方形。

#### IMPL-36: EditorModeContent 裁剪近乎假实现，大图 OOM 风险
- **定位**: `feature-publish/.../EditorModeContent.kt`（L326-346 `applyCropAndFilter` 写死裁剪 70%，`maxOffsetPx=48f` 硬编码）+ L351-358 `decodeBitmap` 固定 `inSampleSize=2`
- **问题**: (1) 裁剪区域写死 70%，拖拽 48 像素才移动到边沿，远小于实际可拖动范围；裁剪框 `fillMaxSize(0.7f)` 居中无法 resize。(2) 4000×6000 大图 inSampleSize=2 仍解码到 2000×3000≈24MB，5 个滤镜 preset 各 `Bitmap.createBitmap` 累计分配，低端机 OOM。(3) Bitmap 不 `recycle()`。
- **影响**: 裁剪体验差；选大图可能 OOM；滤镜切换卡顿。
- **修复策略**: 裁剪改用真实坐标系映射（`onGloballyPositioned` 拿容器尺寸按比例映射）；`decodeBitmap` 先 `inJustDecodeBounds=true` 探测尺寸动态算 `inSampleSize`；缩略图用 32×32；大图用 `ImageDecoder` 替代 `BitmapFactory`。

#### IMPL-37: CapturePreviewBar 缺 key，删除中间项 selected index 错位
- **定位**: `feature-publish/.../CapturePreviewBar.kt`（L55 `itemsIndexed(captures)` 无 key）+ `PublishScreen.kt`（L181-184 删除逻辑）
- **问题**: 删除选中之前的项时 `selectedCaptureIndex` 仍指向原位置，实际列表已前移，会指向下一张甚至越界。例：[A,B,C] 选中 2(C)，删 1(B) → [A,C]，`selectedCaptureIndex=2` 越界，蓝框丢失。
- **修复策略**: `itemsIndexed(captures, key = { _, path -> path })`；删除后若 `index < selectedCaptureIndex` 则 `selectedCaptureIndex--`，若 `index == selectedCaptureIndex` 则置 -1。

#### IMPL-38: AccountDao.getActiveInHour 全表加载后内存过滤
- **定位**: `core-data/.../dao/AccountDao.kt`（L51-57 `getActiveInHour` 先 `getVirtualAccountsList()` 全表取再内存 filter）
- **问题**: `activeWindows` 以 JSON 字符串存储无法 SQL 层按小时槽过滤，每次调度周期全表扫描 + 反序列化 220 条 JSON。`@Transaction` 标注对单次 SELECT 无实际增益。
- **影响**: 账号数增长后调度周期开销增加。
- **修复策略**: 将 24 槽拆为 24 个布尔列（`activeHour0`..`activeHour23`）支持 `WHERE activeHourN=1`；或维护 `account_active_hours(accountId, hour)` 反向索引表。

#### IMPL-39: 多图只发第一张，其余静默丢弃
- **定位**: `feature-publish/.../PublishViewModel.kt`（L137 `mediaPath = current.captures.firstOrNull()`）
- **问题**: `MAX_CAPTURES = 4` 但只取第一张，其余 3 张静默丢弃，UI 不提示。
- **影响**: 用户拍了多张照片以为都发布了，实际只有第一张。
- **修复策略**: 多图存到 `mediaPath` 逗号分隔列表，或新增 `TweetMediaEntity` 表。

### 构建、测试与交付问题（P5）

#### IMPL-40: 各 library 模块 consumer-rules.pro 全为占位
- **定位**: core-data / core-llm / core-scheduler / core-designsystem / 5 个 feature 模块的 `consumer-rules.pro` 与 `proguard-rules.pro` 全为单行注释
- **问题**: library 模块未开启 minify，consumer-rules 是把 keep 规则传递给 app 的唯一渠道。现在全部为空，所有保留责任压在 `app/proguard-rules.pro` 一处。一旦 app 规则遗漏某 library 内部类（如 core-llm 的 `OpenAiApi` 方法签名、core-data 的 `@Serializable` DTO），release 构建运行时崩溃且难定位。
- **影响**: release APK 运行时崩溃风险高，且难定位。
- **修复策略**: 各 library 按自身依赖补充 consumer-rules.pro（core-data: Room Entity/Dao + @Serializable DTO；core-llm: 三个 *Api 接口 + DTO + 拦截器；core-scheduler: @HiltWorker 类）。

#### IMPL-41: 无 MockWebServer / Hilt testing / androidTest 源码，测试基础设施空白
- **定位**: 全局 grep `MockWebServer`/`HiltAndroidRule`/`HiltAndroidTest` 仅在 spec 文档命中；`Glob("**/src/androidTest/**/*.kt")` 返回 No file found
- **问题**: core-llm 有 3 个 Retrofit API 接口无法做 HTTP 层单测；app 声明了 `androidTestImplementation`（espresso/compose-ui-test）但无任何 androidTest Kotlin 文件，依赖白交。core-data / feature 模块 src/test 为空。
- **影响**: 无法验证 LLM 流式解析、DI 图谱、UI 关键流程；RISK-4（SSE 兼容）应对"单元测试覆盖"无法兑现。
- **修复策略**: `libs.versions.toml` 增 `mockwebserver` 别名，core-llm/core-data `testImplementation` 引入；app 加 `androidTestImplementation("com.google.dagger:hilt-android-testing")` + `kspAndroidTest(libs.hilt.compiler)`；至少为 onboarding→feed、发布推文补 Compose UI 测试。

#### IMPL-42: keystore.properties 不存在，release 实际用 debug 签名
- **定位**: `app/build.gradle.kts`（L4-10 从 `keystore.properties` 读取，文件不存在时 fallback debug）+ `.gitignore` 排除 keystore.properties
- **问题**: 当前所有 release 构建实际用 debug 签名，无法上架应用商店。
- **影响**: 无法生成可发布的正式签名 APK。
- **修复策略**: CI/CD 或开发者本地生成 keystore 后写入 `keystore.properties`；或 CI 中通过环境变量注入签名材料。

#### IMPL-43: feature-profile 缺 coil-svg，头像 SVG 无法解码
- **定位**: `feature-profile/build.gradle.kts`（有 `coil-compose` 无 `coil-svg`）+ `feature-feed/.../FeedUtils.kt`（`avatarUriFromSeed` 返回 `.svg` 路径）
- **问题**: feature-profile 若用 `AsyncImage` 加载头像 SVG，因缺 `coil-svg`（`SvgDecoder`）无法解码，头像不显示（静默失败不崩溃）。feature-feed 已自建 `FeedImageLoaderModule` 注册 `SvgDecoder`，但 profile 模块未注册。
- **影响**: 我的页面头像不显示。
- **修复策略**: feature-profile 加 `implementation(libs.coil.svg)`；或在 app 层提供全局带 `SvgDecoder` 的 `ImageLoader` 单例供所有模块复用。

#### IMPL-44: 双 LlmProvider 枚举手工映射，新增 provider 易遗漏
- **定位**: `core-llm/.../LlmClient.kt`（L62 `enum LlmProvider`）+ `core-data/.../config/LlmConfig.kt`（L8 `enum LlmProvider`）+ `app/.../di/AppLlmConfigProvider.kt`（L48-60 `toData()`/`toLlm()` 手工 when 映射）
- **问题**: 两个独立枚举语义重叠但定义不同。新增 provider 必须同步修改两处枚举 + 两处映射，否则 `when` 分支不匹配导致运行时行为错乱。
- **影响**: 维护成本高，新增 provider 易遗漏。
- **修复策略**: 在 core-data 单一定义 `LlmProvider`（带元数据），core-llm 直接依赖 core-data 复用该枚举；或抽取公共 `core-common` 模块承载共享枚举。

#### IMPL-45: ColdStartFiller 默认绑定无法被 app "覆盖"（Hilt 重复绑定）
- **定位**: `feature-onboarding/.../ColdStartFiller.kt`（L51-57 `OnboardingModule.provideColdStartFiller` 已 `@Provides @Singleton`）+ 注释声称"app 模块可提供真实实现覆盖"
- **问题**: Hilt 不支持同组件内同类型的多个 `@Provides` 覆盖——若 app 模块再 `@Provides` 一个 `ColdStartFiller`，会触发 `DuplicateBindings` 编译错误。当前 app 未提供真实实现所以不报错，但"覆盖"的设想无法直接实现。
- **影响**: IMPL-1 的修复受阻——无法简单在 app 模块提供真实 `ColdStartFiller`。
- **修复策略**: onboarding 模块不提供默认绑定（改为 app 必须提供）；或用 `@DefaultColdStartFiller` qualifier 区分；或改用 `@Binds` + `@TestInstallIn` 替换机制。

#### IMPL-46: POST_NOTIFICATIONS / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 声明但未运行时申请
- **定位**: `app/src/main/AndroidManifest.xml`（L27, L34 声明权限）+ 全局搜索无运行时申请代码
- **问题**: (1) Android 13+ `SchedulerForegroundService.startForeground` 的常驻通知因无 `POST_NOTIFICATIONS` 权限不显示。(2) `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 是 Google Play 政策敏感权限，声明但不用可能触发审核问询。(3) `READ_EXTERNAL_STORAGE`（maxSdkVersion=32）实际未使用（EditorModeContent 用 `PickVisualMedia` 系统代理）。
- **影响**: 通知不显示；Play 审核风险；无用权限增加。
- **修复策略**: 在 `MainActivity.onCreate` 用 `rememberLauncherForActivityResult` 申请 `POST_NOTIFICATIONS`；删除未用的 `READ_EXTERNAL_STORAGE`；`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 要么删除要么在引导页添加申请逻辑。

#### IMPL-47: PersonaUpdateWorker 7 天周期 + 20 个账号固定，档位切换不调节
- **定位**: `core-scheduler/.../work/PersonaUpdateWorker.kt`（L54-74 `TARGET_ACCOUNT_COUNT=20` 硬编码）+ `WorkerKeys.kt`（7 天周期）
- **问题**: LOW 与 HIGH 档位下人设更新工作量相同。7 天周期 + 20 个账号，若虚拟账号 100 个，每个账号平均 35 天才更新一次——更新节奏远低于推文生成节奏。
- **影响**: 人设"漂移"修复（RISK-2）响应慢。
- **修复策略**: `TARGET_ACCOUNT_COUNT` 按 level 缩放（LOW=10, MEDIUM=20, HIGH=40）；周期 LOW 档 14 天、HIGH 档 3 天。

#### IMPL-48: 档位切换后已排程 Worker 不会重新入队
- **定位**: `core-data/.../repository/ConfigRepository.kt`（L104-111 `setAiActivityLevel` 仅写 DataStore）+ `core-scheduler/.../SchedulerInitializer.kt`
- **问题**: `setAiActivityLevel` 没有触发任何调度器回调。LOW→HIGH：原本按 LOW 配额排程的 Worker 不会增加，HIGH 的额外配额直到下次 App 启动才被使用。`InteractionWorker`/`PendingInteractionWorker`/`PersonaUpdateWorker` 完全不读 `AiActivityLevel`，档位切换不影响互动频率。
- **影响**: 档位切换对用户体验的反馈滞后。
- **修复策略**: `setAiActivityLevel` 后通知 `SchedulerInitializer` 重新调度（通过 `SharedFlow`）；或 `SchedulerForegroundService` 观察档位变化 cancel 并重新 enqueue。
