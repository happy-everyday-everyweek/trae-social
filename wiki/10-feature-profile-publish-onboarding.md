# feature-profile / publish / onboarding

个人主页、相机发布、首次启动引导三个功能模块。

## feature-profile

namespace `com.trae.social.feature.profile`。依赖 `core-scheduler` / `work-runtime-ktx` / `coil-compose` / `coil-svg` / `material-icons-extended`。

### ProfileScreen

参数 `onNavigateToSettings` / `onNavigateToFollowList`。`TopAppBar` + Settings icon。`ProfileHeader`（72dp avatar / displayName / @username / bio / profession / stats 推文/关注/粉丝）。`CapsuleTab`（TWEETS / MEDIA / LIKES）。`TweetsTab`（`LazyColumn` `ProfileTweetRow` + `animateItem`）。`MediaTab`（`LazyVerticalGrid` 3列 120dp）。`StatItem`（headline + caption1）。

### ProfileViewModel

`@HiltViewModel` 注入 `AccountRepository` / `TweetRepository` / `ConfigRepository` / `FollowRelationDao` / `@ProfileImageLoader ImageLoader`。状态 `_uiState` / `_activityLevel`（默认 MEDIUM）/ `_selectedTab`。

- `tweetsFlow = observeByAuthor(SELF_ID).stateIn(WhileSubscribed(5000))`
- `mediaTweetsFlow = tweetsFlow.filter{mediaPath not blank}`

常量 `SELF_ID = "user-self"`。

### ProfileUtils

`avatarUriFromSeed` / `toImageUri`（多图取第一张）/ `formatRelativeTime` / `formatCount`（万）。

### FollowListScreen

参数 `type: FollowListType` / `onBack`。`LazyColumn` + `FollowAccountRow`（44dp avatar + name + `OutlinedButton` 关注/已关注）。整行点击 Toast"查看 @xxx 的主页（即将开放）"。

`FollowListViewModel`：`@HiltViewModel` 注入 `FollowRelationDao` / `AccountRepository` / `@ProfileImageLoader ImageLoader`。`_followingIds: MutableStateFlow<Set<String>>`。`load(type)` 先获取 `_followingIds` 再按 FOLLOWING / FOLLOWERS `getFollowing` / `getFollowers` + `mapNotNull getById`。`toggleFollow` insert / delete `FollowRelationEntity` + `load` 刷新。`FollowListType` enum`{FOLLOWING, FOLLOWERS}`。

### SettingsScreen

参数 `onBack` / `onNavigateToApiKey` / `onNavigateToDevOptions`。

- AI 活跃度 section（`SocialCard` + `ActivityLevelRow` LOW/MEDIUM/HIGH）
- 高级 section（API Key 管理 / 人设管理 / 清除缓存 / 关于）
- 7 次连点解锁开发者选项 `REQUIRED_TAPS_TO_UNLOCK = 7`，`rememberSaveable` 持久化 `aboutTapCount` / `devOptionsUnlocked`
- 关于 `AlertDialog`（versionName + "再点击关于 N 次可解锁开发者选项"）
- 清除缓存 `AlertDialog`（`clearCache` 递归删除 cacheDir + image_cache）
- `ActivityLevelRow` 显示"约 X 条/天，Y RPM"

### ApiKeyManagementScreen

参数 `onBack`。`LazyColumn` + `ProviderConfigCard`（按 `LlmProvider` 列出）。`mutableStateMapOf<LlmProvider, String>` 用于 drafts。API Key 字段 `OutlinedTextField` + `PasswordVisualTransformation`。"设为默认 provider"按钮 IMPL-27 修复：未配置 Key 时禁用。

`ApiKeyViewModel`：`@HiltViewModel` 注入 `ConfigRepository` / `LlmCacheInvalidator`。`loadAll` 遍历 `LlmProvider.values()` 读 `apiKeyPreview` / `baseUrl` / `modelName` + `defaultProvider`。`setApiKey` / `setBaseUrl` / `setModelName` 每次保存后 `cacheInvalidator.invalidateCache()` + `refreshProvider`。`setDefaultProvider`。

### DevOptionsScreen

参数 `onBack`。当前活跃度档位（name + rpmLimit + dailyPostsPerAccount）。手动触发调度（推文生成 / 互动处理 / 人设处理 / 人设更新 `OutlinedButton` + `Button`）。LLM 调用统计（总调用 / 成功 / 失败 / 限流429 / 成功率 + 按类型分布）。调度日志（`LogRow` action·result + durationMs + timestamp·accountId + errorMessage）。`LaunchedEffect(triggerResult)` -> `Snackbar`。

`DevOptionsViewModel`：`@HiltViewModel` 注入 `appContext` / `SchedulerLogDao` / `ConfigRepository` / `AccountRepository`。`logsFlow = observeRecent(200).stateIn(WhileSubscribed(5000))`。手动触发均 `enqueueUniqueWork(..., REPLACE)`：

- `triggerTweetGeneration`（随机选虚拟账号 + `tweetGenerationRequest`）
- `triggerPendingInteractions`（`OneTimeWorkRequestBuilder<PendingInteractionWorker>`）
- `triggerPersonaUpdate`（`OneTimeWorkRequestBuilder<PersonaUpdateWorker>`）

常量 `LOG_LIMIT = 200`。

### di/ProfileImageLoaderModule

`@ProfileImageLoader` 限定符（IMPL-43 修复 feature-profile 缺 coil-svg）。`ImageLoader.Builder` + `SvgDecoder.Factory` + `crossfade(true)`。

## feature-publish

namespace `com.trae.social.feature.publish`。依赖 `core-scheduler` / `work-runtime-ktx` / `camera-core` / `camera2` / `lifecycle` / `view` / `accompanist-permissions` / `coil-compose`。

### PublishScreen

参数 `onPublished` / `onClose`。`CapsuleTab`（相机/编辑器）`AnimatedContent` fadeIn / fadeOut 200ms。`CaptionInput`（captures 非空时显示）。`CameraModeContent` / `EditorModeContent`。`CapturePreviewBar` + `ActionButton`"发布"。`LaunchedEffect` 收集 `viewModel.events`：

- `Published` -> `showPublishAnimation` 400ms -> `onPublished`
- `PublishFailed` -> `Snackbar`"发布失败，请重试"（IMPL-15 修复：失败保留输入）

`PublishFlyInOverlay` 2 阶段动画：

- 阶段1 0-300ms scale 1->0.3 + translate 400->-200
- 阶段2 300-400ms scale 0.3->0.1 + fade out

常量 `PUBLISH_ANIM_DURATION_MS = 400` / `PHASE_1_RATIO = 0.75f`。

### PublishViewModel

```kotlin
enum class CaptureRatio { SQUARE, RATIO_4_3, RATIO_16_9 }
```

`toCameraXAspectRatio` 中 SQUARE 与 RATIO_4_3 都映射 RATIO_4_3，UI 层裁剪为正方形。

```kotlin
enum class FlashMode { OFF, ON, AUTO }
```

`PublishUiState(captures, caption, selectedRatio, flashMode, isPublishing)`。

```kotlin
sealed class PublishEvent { object Published; object PublishFailed }
```

`_events = Channel<PublishEvent>(BUFFERED).receiveAsFlow()` 一次性事件通道。

方法：

- `addCapture`（max `MAX_CAPTURES = 4`）
- `removeCapture`
- `updateCaption`（max `MAX_CAPTION_LENGTH = 280`）
- `publish`（构建 `TweetEntity(authorId = AUTHOR_SELF, mediaPath = captures.joinToString(","))` + `insertTweet` + `triggerAiInteraction`）
- `triggerAiInteraction`（`WorkerPolicies.interactionRequest` 入队，失败回退从虚拟账号池随机选 ID 落库 LIKE 互动，IMPL-3 修复原硬编码"ai-fallback"）

常量 `MAX_CAPTURES = 4` / `MAX_CAPTION_LENGTH = 280` / `AUTHOR_SELF = "user-self"`。

### CameraModeContent

参数 `ratio` / `flashMode` / `onRatioChange` / `onFlashModeChange` / `onCapture`。`rememberPermissionState(CAMERA)`。`LaunchedEffect(ratio, lensFacing, hasCameraPermission)`：P1-1 修复 ANR `Dispatchers.IO` 中获取 `ProcessCameraProvider`（原主线程阻塞）；`Preview.Builder.setTargetAspectRatio` + `ImageCapture.Builder.setFlashMode.setCaptureMode(MINIMIZE_LATENCY)` + `bindToLifecycle`。`DisposableEffect onDispose unbindAll + executor.shutdown`（P1-3 修复 Tab 切换后传感器持续占用）。1:1 比例叠加黑色遮罩 + 中心正方形预览区。`capturePhoto`：`cacheDir/capture/<timestamp>.jpg` + `OnImageSavedCallback`。`cropToSquare`（IMPL-35/36 修复）：`inJustDecodeBounds` 探测 + 动态 `inSampleSize` 目标 1080px + 中心裁剪 + JPEG 90 避免 OOM。`ShutterButton`（72dp 圆形 + 56dp systemBlue 内圈）。`PermissionRequestCard`（前往设置/再次请求）。`FlashMode.toCameraXFlash()` 映射。

### EditorModeContent

```kotlin
enum class FilterPreset { ORIGINAL, WARM, COOL, GRAYSCALE, SEPIA }
```

每个对应 `ColorMatrix` 5x4 + `apply(source: Bitmap)`。参数 `onEditComplete`。`PickVisualMedia` launcher 首次自动打开相册。

- `CropOverlay`（归一化 Rect [0,1]，拖拽内部整体平移 + 4 `CornerHandle` 调整边角）
- `AspectRatioRow`（自由/1:1/4:3/16:9）
- `FilterRow`（32x32 缩略图 64dp 圆形）
- `computeCenteredCropRect`（考虑 `ContentScale.Fit` 后实际显示区域）
- `applyCropAndFilter`（容器坐标 -> 图片显示像素 -> 源图像素）
- `decodeBitmap`（IMPL-36 修复 `inJustDecodeBounds` + 动态 `inSampleSize` `MAX_DECODE_EDGE = 2048`）
- `saveBitmap`（`cacheDir/edit/<timestamp>.jpg` JPEG 90）
- 中间 `Bitmap recycle()` 避免内存泄漏

常量 `MIN_CROP_RATIO = 0.1f` / `MAX_DECODE_EDGE = 2048`。

### CaptionInput

`TextField` placeholder"说点什么..."maxLines 4。字数计数"123/280"接近上限（`MAX_CAPTION_LENGTH - 20`）时变 systemRed。

### CapturePreviewBar

`LazyRow` 64dp 缩略图 8dp 圆角 selected 蓝色边框 2dp。右上角 16dp 删除按钮（Close icon）。

## feature-onboarding

namespace `com.trae.social.feature.onboarding`。依赖 `core-llm` / `accompanist-permissions`。

### OnboardingNavHost

参数 `onCompleted: (skipped: Boolean) -> Unit`。5 路由 welcome -> provider -> key -> test -> done。`OnboardingProgressIndicator`（5 段细线 reached = systemBlue 未来 = tertiaryBackground semantics"步骤 N/M"）。`Column` 统一 `statusBarsPadding` + `navigationBarsPadding`（IMPL-49/#41 修复：标题被状态栏遮挡）。

流程：

- `WelcomeScreen.onStart` -> `navigate PROVIDER`
- `.onSkip` -> `viewModel.skip` + `onCompleted(true)`
- `ConnectionTestScreen.onComplete` -> `viewModel.saveAndComplete` + `navigate DONE`（`popUpTo KEY inclusive`）
- `DoneScreen.onCompleted` -> `onCompleted(false)`

### OnboardingViewModel

`@HiltViewModel` 注入 `ConfigRepository` / `LlmProviderRegistry` / `ColdStartFiller`。`OnboardingUiState(selectedProvider, apiKey, baseUrl, model, testStatus, isSaving, completed)`。

```kotlin
sealed class TestStatus {
    object Idle
    object Loading
    object Success
    data class Error(message)
}
```

方法：

- `selectProvider`（重置 baseUrl / model 为该 provider 默认值 + `testStatus = Idle`）
- `updateApiKey`
- `updateBaseUrl`
- `updateModel`（trim + 重置 testStatus）
- `testConnection`（`persistConfig` + `invalidateCache` + `client.ping`，失败 `classifyErrorByProbing` 调 `chatSync` 捕获具体异常）
- `saveAndComplete`（`persistConfig` + `setDefaultProvider` + `invalidateCache` + `coldStartFiller.triggerInitialFill`（RISK-14）+ `completed = true`）
- `skip`

`classifyError`（`UnknownHostException` -> DNS，`SocketTimeoutException` -> 超时，`HttpException` 401 / 403 / 404 / 429）。

默认配置 `DEFAULT_BASE_URLS`（`DEFAULT_MODELS`：OpenAI gpt-4o-mini，Anthropic claude-3-5-sonnet-20240620，Gemini gemini-1.5-flash，CUSTOM gpt-4o-mini）。

### ColdStartFiller

```kotlin
interface ColdStartFiller { suspend fun triggerInitialFill() }
```

`DefaultColdStartFiller` `@Singleton` `@Inject` no-op 占位。IMPL-45 修复：app 模块必须提供真实绑定避免 `DuplicateBindings`。

### Screens

- `WelcomeScreen`（`WelcomeIllustration` 180dp 渐变圆 + `BulletPoint` + `DisclaimerCard` + `ActionButton`"开始配置" + `TextButton`"稍后"）
- `ProviderSelectScreen`（`PROVIDER_OPTIONS`：OpenAI #10A37F"O" / Anthropic #D97757"A" / Google Gemini #4285F4"G" / 自定义 #6B7280"C"，`ProviderCard` 48dp circle logo + 选中蓝色边框 2dp + 蓝色圆点）
- `KeyInputScreen`（`OutlinedTextField` API Key + `PasswordVisualTransformation` + Visibility toggle / Base URL `KeyboardType.Uri` `isHttpUrl` 校验 / Model name / `canSubmit` apiKey 非空 && `isHttpUrl(baseUrl)` && model 非空 / `customUrlMissing` 错误 CUSTOM 必填 Base URL）
- `ConnectionTestScreen`（`IdleState` 80dp circle"?" + 测试连接 button / `LoadingState` `LoadingShimmer` / `SuccessState` 80dp green circle + Check + 完成 / `ErrorState` 80dp red circle + Close + 消息卡片 + 返回修改/重试）
- `DoneScreen`（`LaunchedEffect autoDismissMs = 1500L` -> `onCompleted`，96dp green circle + Check 48dp + "配置完成" title1 + "进入应用" `ActionButton`）
