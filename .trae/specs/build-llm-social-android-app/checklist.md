# Checklist

> 风险核对项标记 [RISK-n]，对应 spec.md 实现风险章节，便于交付前专项检查。
>
> **实现问题专项检查**标记 [IMPL-n]，对应 spec.md "实现问题深度分析" 章节（IMPL-1 ~ IMPL-48）。这些是已实现代码（Tasks 1-14 完成）的实际审计发现，**必须在 Task 15 联调前修复 P0 阻断项**，否则核心链路不通。

## 工程与构建
- [ ] 多模块 Gradle 工程结构创建完成（app + 4 个 core + 5 个 feature 模块）
- [ ] `libs.versions.toml` 集中管理所有依赖版本
- [ ] `AndroidManifest.xml` 声明全部所需权限（网络、相机、存储、前台服务、调度、电池优化白名单、通知、开机启动）
- [ ] Hilt / Room / Retrofit / Coil / WorkManager / CameraX / DataStore / Paging 依赖全部接入并可编译
- [ ] `SocialApp` Application 类与 Hilt 入口配置完成，含全局异常处理
- [ ] `./gradlew assembleRelease` 可成功产出 APK 文件
- [ ] 最低 SDK 26，目标 SDK 34，compileSdk 34
- [ ] ABI 拆分配置生效（arm64-v8a / armeabi-v7a / x86_64）
- [ ] [RISK-10] release APK 体积 ≤ 50MB
- [ ] [RISK-11] `android:allowBackup="false"`
- [ ] R8 混淆规则完整（Hilt / Room / Retrofit / Kotlinx Serialization / Compose 保留）

## 设计系统
- [ ] Color Tokens 覆盖明 / 深色双模式，含 systemBlue 等系统色
- [ ] Typography 体系完整（largeTitle / title1-3 / headline / body / callout / subheadline / footnote / caption1-2）
- [ ] `GlassBlurContainer` 磨砂玻璃组件可用，API 31+ 使用 RenderEffect
- [ ] [RISK-6] API 26-30 磨砂玻璃降级为半透明纯色
- [ ] [RISK-6] 低内存设备（`isLowRamDevice`）强制降级模糊
- [ ] [RISK-6] 滚动时模糊半径减半，避免帧率骤降
- [ ] 通用组件齐全（Avatar、ActionButton、CapsuleTab、SocialSheet、SocialCard、SocialDivider、LoadingShimmer）
- [ ] `AppTheme` 正确桥接 Material3，深色模式自动切换
- [ ] 状态栏透明 + 图标色随主题

## 数据层
- [ ] 全部实体定义完成（Account、Tweet、Media、Interaction、FollowRelation、PersonaDynamicField、UserConfig、SchedulerLog、ImageUsage）
- [ ] `AccountEntity` 包含全部固定字段（worldview/values/languageStyle/catchphrase/emojiPreference/typoRate/activeWindows）与动态字段
- [ ] `TweetEntity` 含 `deduplicationKey`（unique）与 `isAiGenerated` 标记
- [ ] DAO 与 `AppDatabase` 实现并配置外键与迁移策略
- [ ] `AppDatabase` 启用 `exportSchema=true`，schema JSON 输出至 `schemas/`
- [ ] 关键索引：`tweets.createdAt`、`tweets.authorId`、`tweets.deduplicationKey`（unique）、`interactions.scheduledAt`
- [ ] Repository 层完整（Account / Tweet / Interaction / Config）
- [ ] [RISK-11] API Key 通过 EncryptedSharedPreferences 加密存储
- [ ] 非敏感配置通过 DataStore Preferences 持久化
- [ ] 种子数据加载器可从 `assets/personas/` 异步导入 200+ 账号，不阻塞 UI
- [ ] 种子加载幂等（accounts 表非空时跳过）
- [ ] [RISK-14] 导入人设自带历史推文（每账号 5-10 条，时间戳分布于启动前 1-30 天）
- [ ] [RISK-9] 发布版配置显式 Room Migration（非 destructive）

## LLM 集成
- [ ] `LlmClient` 接口定义完成（`chat` Flow / `chatSync` / `ping`）
- [ ] OpenAI 提供商实现（兼容自定义 Base URL，支持 SSE 流式）
- [ ] Anthropic 提供商实现（Messages API + SSE 流式，system 顶层字段）
- [ ] Google Gemini 提供商实现（含 streamGenerateContent，HTTP chunked 解析）
- [ ] `LlmProviderRegistry` 支持运行时切换提供商
- [ ] 连通性测试 `ping()` 可验证 Key 有效性，返回明确错误码
- [ ] 拦截器链：AuthInterceptor → LoggingInterceptor（Key 脱敏）→ RetryInterceptor（指数退避）
- [ ] [RISK-4] 各提供商 SSE 解析有 MockWebServer 单元测试覆盖
- [ ] [RISK-4] 流式失败降级为非流式调用
- [ ] [RISK-13] JSON 解析失败降级为纯文本推文
- [ ] [RISK-13] OpenAI 优先使用 `response_format` JSON mode
- [ ] [RISK-11] 日志中 API Key 脱敏（仅显示前 4 + 后 4）

## Prompt 工程
- [ ] 推文生成 Prompt 模板完成（system 注入人设全部固定字段）
- [ ] 推文生成输出 JSON：`{text, withImage, imageTheme, interactionTendency}`
- [ ] 推文长度硬限制 ≤ 280 字符，超长截断
- [ ] 后处理：按 `typoRate` 注入错别字、按 `emojiPreference` 追加 emoji
- [ ] 评论生成 Prompt 模板完成（支持批量生成 3-5 条）
- [ ] 人设动态字段更新 Prompt 模板完成
- [ ] [RISK-2] 人设动态字段更新时 embedding cosine > 0.5 校验，差异过大回退
- [ ] [RISK-2] 敏感词过滤（暴力/仇恨/色情）
- [ ] [RISK-12] Prompt 含"输出前检查内容合规"指令

## 配图本地图库
- [ ] 8 个主题分类（landscape/food/city/pet/sport/art/tech/nature）
- [ ] 每类 ≥ 25 张，总计 ≥ 200 张
- [ ] [RISK-10] 图片为 WebP 格式，单张 < 100KB
- [ ] [RISK-10] 图库总体积 < 20MB
- [ ] `LocalImageGallery` 接口可按主题分类随机选取
- [ ] 配图去重：30 天内同账号不重复使用同图（基于 `ImageUsageEntity`）
- [ ] 主题图片耗尽时 fallback 至相邻主题
- [ ] 推文生成流程接入：`withImage=true` 时调用图库

## 虚拟账号人设
- [ ] 人设生成脚本可产出 ≥ 220 个不重复人设
- [ ] 矩阵分布：20 职业 × 4 年龄段 × 6 文化背景
- [ ] 每个人设含完整固定字段（worldview/values/languageStyle/catchphrase/emojiPreference/typoRate/activeWindows）
- [ ] `activeWindows` 为 24 槽 bool 数组，基于职业生成
- [ ] [RISK-5] 人设三元组（worldview+values+languageStyle）相似度 < 0.85
- [ ] 每个人设有确定性头像（不依赖 AI 图像生成）
- [ ] 每人设自带 5-10 条历史推文种子
- [ ] `assets/personas/` 分片存储（personas_001.json ~ personas_011.json）
- [ ] `validate.py` 校验通过（数量、username 唯一、字段完整、三元组无重复）
- [ ] [RISK-10] 人设 JSON 总体积 < 1MB

## AI 调度系统
- [ ] 调度规则模型可解析 `activeWindows` 并计算下一次触发时刻
- [ ] `missedWindows` 可识别错过的活跃窗
- [ ] `TweetGenerationWorker` 可按时触发并生成推文落库
- [ ] `InteractionWorker` 可监听用户新推文并执行延迟互动
- [ ] [RISK-1] 全局令牌桶限流器（默认 30 RPM，可配）
- [ ] [RISK-1] 单账号每日推文上限（默认 4 条）
- [ ] [RISK-1] "AI 活跃度"档位（低/中/高）可调
- [ ] [RISK-1] 429 响应直接跳过该次调度
- [ ] [RISK-1] 评论生成批量化（一次调用 3-5 条）
- [ ] 互动延迟符合对数正态分布（点赞 30s-5min、评论 2-15min、转发 5-30min）
- [ ] 互动相关性匹配（按 bio 关键词 + 职业重合度选 3-8 账号）
- [ ] `PersonaUpdateWorker` 可周期更新人设动态字段
- [ ] 前台服务 `SchedulerForegroundService` 保活 + 通知（`dataSync` 类型）
- [ ] [RISK-3] 应用启动时检查并补发错过的活跃窗推文
- [ ] [RISK-3] `BootReceiver` 开机后延迟启动服务
- [ ] [RISK-3] 引导用户加入电池优化白名单
- [ ] [RISK-3] 关键调度 `AlarmManager.setExactAndAllowWhileIdle` 兜底
- [ ] [RISK-3] 严重延迟时用户打开应用即时补发
- [ ] 调度幂等：`deduplicationKey`（accountId + windowStart + sequenceNo）去重
- [ ] [RISK-15] 调度事件全部写入 `scheduler_log` 表
- [ ] [RISK-15] 开发者选项可查看调度日志、手动触发、查看 LLM 调用统计

## UI - 首次启动引导
- [ ] 引导首页 + 提供商选择 + Key 输入 + 连通性测试 + 完成页全部实现
- [ ] [RISK-12] 首次启动展示免责声明（说明内容为 AI 生成）
- [ ] 跳过引导后进入主界面并顶部 banner 提示配置
- [ ] 连通性测试失败显示具体错误码（401/超时/DNS）
- [ ] [RISK-14] 完成引导后触发冷启动内容填充（20 个高活跃账号即时生成 1-2 条）
- [ ] API Key 保存至 EncryptedSharedPreferences

## UI - 主框架与导航
- [ ] `MainActivity` + Compose NavHost 搭建完成
- [ ] 底部 iOS 26 风格磨砂玻璃 Tab 栏（首页 / 时间线 / 我的）
- [ ] Tab 栏右侧独立等高发布按钮
- [ ] Tab 选中态视觉反馈（图标填充 + 下方圆点）
- [ ] 页面切换转场动画（发布页从底部滑入）
- [ ] Tab 状态保持（滚动位置、选中态）

## UI - 首页信息流
- [ ] 推文卡片组件完整（头像、显示名、用户名、时间、文本、图片、互动栏）
- [ ] [RISK-12] AI 推文头像右下角小蓝点标识
- [ ] 分页加载（下拉刷新 + 上拉加载更多，每页 20 条）
- [ ] 推文超 280 字符折叠 + "展开全文"
- [ ] 点赞 / 评论 / 转发 / 收藏交互可用
- [ ] 点赞乐观更新（本地立即 +1）
- [ ] 评论弹层与评论列表
- [ ] 图片大图查看器（双指缩放 + 双击切换）
- [ ] [RISK-6] 滚动帧率 > 50fps（中端机）
- [ ] 离线浏览已加载内容

## UI - 时间线（朋友圈式）
- [ ] 按日期分组的朋友圈式布局
- [ ] 1/2/3/4+ 张图片差异化布局
- [ ] 大图浏览器（左右滑动 + 缩放）
- [ ] 空状态插画 + 引导文案

## UI - 我的页面
- [ ] 个人资料卡片（头像、显示名、bio、统计数字、banner）
- [ ] 推文 / 媒体 / 喜欢 三 Tab
- [ ] 编辑资料（头像、显示名、bio）可保存
- [ ] 设置入口（API Key 管理、人设管理、AI 活跃度、清除缓存、关于、开发者选项）
- [ ] [RISK-15] 开发者选项（连点 7 次解锁）：调度日志、手动触发、LLM 统计
- [ ] 关注 / 粉丝列表页

## UI - 相机式发布
- [ ] 顶部胶囊形横向 Tab（相机 / 编辑器）
- [ ] 相机模式：CameraX 取景 + 比例切换（1:1 / 4:3 / 16:9）+ 拍照
- [ ] 切换前后摄
- [ ] 相机权限缺失显示请求卡片
- [ ] 底部实时预览已拍照片缩略图（最多 4 张）
- [ ] [RISK-8] 缩小飞入信息流动画（或降级 fade+scale）
- [ ] 图片顶部文本输入区（280 字符计数）
- [ ] 编辑器模式：相册选择 + 裁剪 + 滤镜（3-5 预设）
- [ ] 发布逻辑：落库 + 进入信息流顶部 + 触发 AI 互动
- [ ] [RISK-7] CameraX 比例不支持时 fallback 至最近支持比例

## 拟真度与集成
- [ ] AI 调度 → 推文生成 → 信息流展示全链路联调通过
- [ ] 互动延迟、错别字、口癖、emoji 调优符合真人节奏
- [ ] [RISK-2] AI 内容不偏离人设（温和人设不输出攻击性内容）
- [ ] [RISK-12] AI 推文不涉及真实人物姓名的虚假陈述
- [ ] API Key 失效：信息流顶部 banner 提示
- [ ] 网络断开：信息流继续展示本地数据，发布推文本地暂存
- [ ] LLM 限流：调度降频，通知栏提示
- [ ] 列表滚动帧率 > 50fps（中端机）
- [ ] 图片内存占用优化（Coil 缓存配置）
- [ ] 冷启动 < 2s

## 测试与交付
- [ ] core-data 单元测试：DAO 查询（in-memory DB）、Repository
- [ ] core-llm 单元测试：各提供商 SSE 解析（MockWebServer）、Registry 切换
- [ ] core-scheduler 单元测试：时间窗解析、令牌桶、幂等去重
- [ ] UI 测试：引导流程、Tab 导航、信息流滚动、发布流程、评论弹层
- [ ] 人设数据校验测试通过（≥ 220、username 唯一、字段完整、三元组无重复）
- [ ] 集成测试：Mock LLM 下调度→生成→落库→UI 展示全链路
- [ ] 测试覆盖率核心模块 ≥ 70%
- [ ] `./gradlew test` 全绿
- [ ] [RISK-10] release APK 体积 ≤ 50MB 验证
- [ ] [RISK-11] release 签名配置完成
- [ ] release APK 成功构建
- [ ] 在 Android 12 / 13 / 14 模拟器各安装冒烟测试通过
- [ ] 完成引导 → 浏览信息流 → 发布推文 → 查看时间线与我的 全流程无崩溃

---

## 实现问题专项检查（Implementation Issue Special Checks）

> 以下检查项对应 spec.md "实现问题深度分析" 章节（IMPL-1 ~ IMPL-48），基于已实现代码的实际审计。**P0 项必须在 Task 15 联调前全部修复**，否则核心链路不通。修复走 git 分支流程（feature/* → merge --no-ff to main）。

### P0 阻断级（必须修复，否则核心链路不通）
- [ ] [IMPL-1] **PersonaSeeder.seedIfNeeded() 被 SocialApp.onCreate 或 MainActivity 调用**——验证 DB 中 accounts 表有 220 条、tweets 表有 ~1500 条历史推文
- [ ] [IMPL-2] **feature-profile 完整实现**——ProfileViewModel / DevOptionsScreen / ApiKeyManagementScreen / PersonaListScreen / FollowListScreen 文件存在且功能可用，不再仅占位 `Text("我的（待实现）")`
- [ ] [IMPL-3] **InteractionWorker 对 authorId="user-self" 不短路**——验证用户发布推文后能收到 AI 点赞/评论/转发（PersonaSeeder 插入 user-self 账号 或 Worker 走特殊路径）
- [ ] [IMPL-4] **missedWindows 返回带日期的结构**——验证跨日补发时 deduplicationKey 不冲突，昨日 9 点与今日 9 点生成不同 key
- [ ] [IMPL-5] **Worker 入队用 enqueueUniqueWork + InteractionEntity 增 (tweetId,accountId,type) 唯一索引**——验证重复初始化不产生重复互动，单条用户推文互动数 3-8 条
- [ ] [IMPL-6] **markExecuted 与 updateCount 在同一 Room @Transaction**——验证崩溃后 likeCount/commentCount 不丢失
- [ ] [IMPL-7] **RetryInterceptor 不返回已关闭 Response**——验证 429/5xx 持续时 LLM 调用有错误信号而非空字符串
- [ ] [IMPL-8] **流式调用已 emit token 后中断有错误信号**——验证调用方能知晓内容不完整，不写入残缺推文

### P1 功能错误级（功能与设计不符，影响体验）
- [ ] [IMPL-9] **PersonaSeeder 历史推文时间戳用减号** `now - daysAgo*DAY_MS - withinDayOffset`——验证历史推文不浮到信息流最前
- [ ] [IMPL-10] **EncryptedSharedPreferences 提供方有 try-catch + 恢复策略**——验证 Keystore 损坏时 app 不崩溃，能重建空实例
- [ ] [IMPL-11] **TweetCard 的 displayLikeCount = tweet.likeCount**（不再 +1）——验证点赞瞬间显示数与 DB 一致
- [ ] [IMPL-12] **user-self 推文显示用户资料**（非"未知用户"）——验证 PersonaSeeder 插入 user-self 账号 或 resolveAuthor 走特殊分支
- [ ] [IMPL-13] **ConfigRepository 有 onboardingSkipped 字段 + FeedScreen 有 banner**——验证跳过引导后主界面有"前往设置"提示
- [ ] [IMPL-14] **LlmProviderRegistry.getClient 改 suspend 或 AppLlmConfigProvider 加缓存**——验证引导连通性测试点击后 UI 不卡顿
- [ ] [IMPL-15] **publish() 失败 emit Failed 而非 Published**——验证 DB 写入失败时用户看到错误提示
- [ ] [IMPL-16] **AccountEntity 新增 timezone 字段**——验证跨时区旅行时 activeWindows 与配额计数不漂移
- [ ] [IMPL-17] **SchedulerForegroundService 改 specialUse 或去掉常驻**——验证 Android 14+ 不被 6 小时配额杀掉，Play 审核合规
- [ ] [IMPL-18] **BootReceiver 用 OneTimeWorkRequest + setInitialDelay**——验证开机后调度器能启动，注册 LOCKED_BOOT_COMPLETED
- [ ] [IMPL-19] **RetryInterceptor 对 429 抛 RateLimitedException 不重试 + 各 Worker 统一处理**——验证 429 场景配额不浪费
- [ ] [IMPL-20] **对数正态分布用 nextGaussian()*std+mean**——验证互动延迟分布符合产品预期
- [ ] [IMPL-21] **LIKE/FOLLOW 短延迟用 OneTimeWorkRequest + setInitialDelay**——验证 LIKE 30s-5min 内执行，像真人"秒赞"

### P2 数据完整性级
- [ ] [IMPL-22] **所有实体声明 @ForeignKey + onDelete=CASCADE**——验证删除账号不产生孤儿记录（schema JSON foreignKeys 非空）
- [ ] [IMPL-23] **fallbackToDestructiveMigration 改为 OnDowngrade 或 release 用显式 Migration**——验证 schema 升级时用户数据不丢
- [ ] [IMPL-24] **PersonaSeeder 每文件 accounts+tweets 在同一事务**——验证崩溃后数据完整，幂等检查同时校验 tweets 数量
- [ ] [IMPL-25] **AccountDao.updateDynamicFields 用 @Transaction**——验证人设详情页与列表页显示一致

### P3 健壮性与可观测性级
- [ ] [IMPL-26] **core-llm RateLimiter 支持 updateMaxTokens 或统一一层限流**——验证 HIGH 档位 HTTP 吞吐达 60 RPM
- [ ] [IMPL-27] **AuthInterceptor 缺 Key 时抛异常**——验证 ping() 能区分"网络问题"vs"Key 未配置"
- [ ] [IMPL-28] **AnthropicClient 处理 SSE error 事件**——验证 quota 超限/内容被拦截时有错误信号
- [ ] [IMPL-29] **ContentFilter 敏感词匹配排除"反/防/打击"前缀**——验证"反诈骗""反勒索"等合法讨论不误伤
- [ ] [IMPL-30] **SchedulerRateLimiter 不在 Worker 内 reconfigure**——验证多 Worker 并发 + 档位切换时速率符合预期
- [ ] [IMPL-31] **RateLimiter.acquire 计算精确 delay + refillLocked 处理时钟回拨**——验证高并发下不忙等，时钟回拨不锁死
- [ ] [IMPL-32] **RetryInterceptor 内 Timber.d 记录每次重试**——验证重试可观测

### P4 UI/UX 与性能级
- [ ] [IMPL-33] **GlassBlurContainer 用 RenderEffect 模糊背后内容 + provideIsScrolling 接入**——验证底栏真正毛玻璃效果，滚动时半径减半
- [ ] [IMPL-34] **FullScreenImage 用 pointerInput(scale) 自行处理手势 + 平移 clamp**——验证缩放=1 时 pager 翻页灵敏，放大后不拖丢
- [ ] [IMPL-35] **CameraX 1:1 比例加非透明黑边 + Bitmap 中心裁剪**——验证 1:1 拍照体验名副其实
- [ ] [IMPL-36] **EditorModeContent 裁剪用真实坐标系 + decodeBitmap 动态 inSampleSize + 缩略图 32×32**——验证大图不 OOM，裁剪可精确定位
- [ ] [IMPL-37] **CapturePreviewBar itemsIndexed 加 key + 删除后 selectedCaptureIndex 调整**——验证删除中间项蓝框不丢失
- [ ] [IMPL-38] **AccountDao.getActiveInHour 拆 24 布尔列或反向索引表**——验证调度周期不全表扫描
- [ ] [IMPL-39] **多图发布支持**——验证 MAX_CAPTURES=4 时 4 张图都发布，不静默丢弃

### P5 构建测试交付级
- [ ] [IMPL-40] **各 library 模块 consumer-rules.pro 补充**——验证 release 构建运行时不崩溃（core-data/core-llm/core-scheduler/feature 各自保留规则）
- [ ] [IMPL-41] **MockWebServer + Hilt testing + androidTest 源码补齐**——验证 `./gradlew test` 与 `./gradlew connectedAndroidTest` 有实际测试运行
- [ ] [IMPL-42] **keystore.properties 存在且用正式签名**——验证 release APK 用非 debug 签名
- [ ] [IMPL-43] **feature-profile 加 coil-svg 或 app 提供全局 ImageLoader**——验证我的页面头像显示
- [ ] [IMPL-44] **统一 LlmProvider 枚举（单一定义）**——验证新增 provider 不需改两处
- [ ] [IMPL-45] **ColdStartFiller 绑定机制可被 app 替换**——验证 onboarding 不提供默认绑定 或 用 qualifier 区分
- [ ] [IMPL-46] **Manifest 权限清理 + POST_NOTIFICATIONS 运行时申请**——验证通知显示，无无用权限
- [ ] [IMPL-47] **PersonaUpdateWorker 按 level 缩放**——验证 LOW=10/MEDIUM=20/HIGH=40，周期可调
- [ ] [IMPL-48] **档位切换后重新调度**——验证 LOW→HIGH 后 Worker 立即增加，反馈不滞后

### 交叉验证（跨问题一致性）
- [ ] **user-self 账号一致性**：IMPL-1（PersonaSeeder 插入）、IMPL-3（InteractionWorker 不短路）、IMPL-12（resolveAuthor 显示资料）三处修复一致
- [ ] **冷启动填充链路**：IMPL-1（PersonaSeeder 调用）→ IMPL-45（ColdStartFiller 绑定）→ 引导完成触发——三处通畅
- [ ] **429 处理一致性**：IMPL-19（RetryInterceptor 抛异常）+ IMPL-26（RateLimiter）+ 各 Worker 统一捕获——三处协调
- [ ] **时区一致性**：IMPL-16（AccountEntity timezone）+ ScheduleRuleResolver + DailyQuotaChecker——三处用同一 zone
- [ ] **事务一致性**：IMPL-6（PendingInteractionWorker）+ IMPL-24（PersonaSeeder）+ IMPL-25（updateDynamicFields）——三处都用 @Transaction
