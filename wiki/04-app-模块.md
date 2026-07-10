# app 模块

应用入口模块，namespace `com.trae.social.app`，applicationId `com.trae.social`（debug 加 `.debug` 后缀）。包含 Application、MainActivity、导航、DI 装配。

## SocialApp（Application 类）

- `class SocialApp : Application(), Configuration.Provider`，标注 `@HiltAndroidApp`。
- 实现 `androidx.work.Configuration.Provider` 接入 `HiltWorkerFactory`，使 `@HiltWorker` 可注入。
- 注入字段：
  - `workerFactory: HiltWorkerFactory`
  - `personaSeeder: PersonaSeeder`
- `appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`，子协程异常不互相取消。
- `onCreate` 流程：
  1. `initTimber()`
  2. `installCrashHandler()`
  3. 在 `appScope.launch` 内 `runCatching { personaSeeder.seedIfNeeded().collect {...} }.onFailure { Timber.e }`
- 调度器初始化已移至 `MainActivity.onCreate`（注释说明：`Application.onCreate` 运行于后台上下文，Android 12+ 从后台启动前台服务会抛 `ForegroundServiceStartNotAllowedException`）。
- `workManagerConfiguration` getter 返回：

```kotlin
Configuration.Builder()
    .setWorkerFactory(workerFactory)
    .setMinimumLoggingLevel(Log.INFO)
    .build()
```

### initTimber

- DEBUG 时 plant 自定义 `DebugTree`（tag 格式 `Social/<文件名>:<行号>`）。
- release plant `ReleaseTree`。

### installCrashHandler

- 保存默认 `UncaughtExceptionHandler` 为 `previousHandler`。
- 设置新 handler 记录 `Timber.e` 后委托 `previousHandler` 终止进程。

### ReleaseTree 脱敏

- `isLoggable` 仅 `priority >= WARN`。
- `log` 调用 `sanitize(message)`。
- `sanitize` 正则脱敏规则（按顺序）：
  1. Bearer token -> `$1***`
  2. 长度 >= 20 的疑似密钥串 -> `***`
  3. 邮箱 -> `***@***`
  4. 中国大陆手机号 `1[3-9]\d{9}` -> `1**********`

## MainActivity

- `class MainActivity : ComponentActivity()`，`@AndroidEntryPoint`，注入 `configRepository: ConfigRepository`。
- `onCreate`：
  1. `enableEdgeToEdge()`
  2. `SchedulerInitializer.initialize(this)`（前台上下文，`SchedulerInitializer` 内部有 `AtomicBoolean` 幂等守卫）
  3. `setContent { SocialTheme { SocialApp(configRepository) } }`

### SocialApp composable

- `rememberNavController`。
- 通知权限申请（API 33+ 检查 `POST_NOTIFICATIONS`，未授予则 `launcher.launch`，回调为空不阻塞）。
- 用 `produceState<Boolean?>(null)` 异步读 `isOnboardingCompleted()`，未读到前显示空 `Box` 占位避免起始路由闪烁。
- 顶层 `NavHost` startDestination 根据 `done` 取 `MAIN` 或 `ONBOARDING`。
- `onCompleted` 回调：`scope.launch` 内 `setOnboardingCompleted(true)` + `setOnboardingSkipped(skipped)`，`navigate(MAIN){ popUpTo(ONBOARDING){ inclusive=true } }`。

### MainScaffold

- 内嵌 `NavHost`，`currentBackStackEntryAsState` 取 `currentRoute`。
- `showBottomBar = currentRoute in {FEED, TIMELINE, PROFILE}`，其余路由隐藏底栏。
- `isScrolling` 状态由 feed/timeline 的 `onScrollingChange` 回调驱动，`provideIsScrolling` 包裹 `Scaffold` 使 `GlassBlurContainer` 滚动时减半模糊半径。

### Tab 切换导航策略

```kotlin
popUpTo(graph.findStartDestination().id){ saveState=true } + launchSingleTop + restoreState
```

### 转场动画表

| 路由 | enter | exit | popEnter | popExit |
| --- | --- | --- | --- | --- |
| FEED / TIMELINE / PROFILE / SETTINGS / API_KEY / DEV_OPTIONS / FOLLOW_LIST | `fadeIn()` | `fadeOut()` | `fadeIn()` | `fadeOut()` |
| PUBLISH | `slideInVertically(initialOffsetY={it})` | `fadeOut()` | `fadeIn()` | `slideOutVertically(targetOffsetY={it})` |

- `FOLLOW_LIST` 带 `navArgument` `type=StringType`，解析枚举容错降级为 `FOLLOWING`。

## AppRoutes（路由常量）

`object AppRoutes` 集中定义：

| 常量 | 值 |
| --- | --- |
| `ONBOARDING` | `"onboarding"` |
| `MAIN` | `"main"` |
| `FEED` | `"feed"` |
| `TIMELINE` | `"timeline"` |
| `PROFILE` | `"profile"` |
| `SETTINGS` | `"settings"` |
| `API_KEY` | `"apikey"` |
| `DEV_OPTIONS` | `"devoptions"` |
| `PUBLISH` | `"publish"` |
| `FOLLOW_LIST` | `"followlist/{type}"` |
| `FOLLOW_LIST_TYPE_ARG` | `"type"` |

- 工具函数 `followList(type): String = "followlist/$type"`。

## SocialBottomBar

- `TabSpec(route, icon: ImageVector, label)`。
- `MainTabs` 三项：
  - 首页 `Icons.Filled.Home`
  - 时间线 `Icons.Filled.GridView`
  - 我的 `Icons.Filled.Person`
- 外层 `GlassBlurContainer(fillMaxWidth + navigationBarsPadding)`，内层 `Row` 高 `56dp`，三 Tab 各 `weight(1f)` + `Spacer(16dp)` + `PublishButton`。

### TabItem

- 选中色 `systemBlue`，未选中 `tertiaryLabel`。
- `animateColorAsState` + `Spring.DampingRatioMediumBouncy`。
- 底部 `4dp` 圆点 `animateFloatAsState` 缩放进场。
- `4dp` 占位保持布局稳定。

### PublishButton

- `56dp` 圆形 FAB，`systemBlue` 背景 + 白色 `Add 28dp`。
- 按压反馈 `MutableInteractionSource` + `collectIsPressedAsState`，`scale` `animateFloatAsState(0.92f if pressed)`。

## DI 模块（app/di）

### 1. AppColdStartFiller

- `@Singleton`，实现 feature-onboarding 的 `ColdStartFiller` 接口。
- `triggerInitialFill()`：取当前小时活跃账号（最多 `MAX_COLD_START_ACCOUNTS=20`），为每个入队 `TweetGenerationWorker` 立即生成 1 条推文。
- `deduplicationKey="coldstart_${account.id}_$windowStart"` 保证幂等。
- 空活跃账号则跳过。

### 2. AppLlmConfigProvider

- `@Singleton`，实现 core-llm 的 `LlmConfigProvider`。
- 全部方法 `suspend` 转发到 `ConfigRepository`：`getApiKey` / `getBaseUrl` / `getModel`。
- `getDefaultProvider` 默认回退 `OPENAI`。

### 3. AppOnboardingModule

- `@Module @InstallIn(SingletonComponent)`，仅提供 `ColdStartFiller` 绑定（feature-onboarding 不再提供默认绑定以避免 `DuplicateBindings`）。

### 4. AssetProviderModule

- 含 `AssetProviderImpl`（`listAssets` / `openAsset` 委托 `context.assets`）。
- 三个绑定：`AssetProvider`、`LlmConfigProvider`、`LlmCacheInvalidator`（lambda -> `registry.invalidateCache()`）。

### 绑定总结表

| 接口 | 实现 | 提供模块 | 限定符 |
| --- | --- | --- | --- |
| `ColdStartFiller` | `AppColdStartFiller` | `AppOnboardingModule` | - |
| `LlmConfigProvider` | `AppLlmConfigProvider` | （类自带 `@Singleton` 构造注入） | - |
| `AssetProvider` | `AssetProviderImpl` | `AssetProviderModule` | - |
| `LlmCacheInvalidator` | lambda -> `registry.invalidateCache()` | `AssetProviderModule` | - |

## AndroidManifest 要点

### 权限分组

- 网络：`INTERNET`、`ACCESS_NETWORK_STATE`
- 相机与媒体：`CAMERA`、`READ_MEDIA_IMAGES`（`READ_EXTERNAL_STORAGE` 已移除，用 `PickVisualMedia` 系统代理）
- 前台服务：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`
- 通知：`POST_NOTIFICATIONS`
- 开机自启/唤醒：`RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK`
- `uses-feature camera required=false`

### application

- `name=.SocialApp`
- `allowBackup=false`
- `theme=@style/Theme.Social`
- `supportsRtl=true`
- `tools:targetApi="34"`

### 组件

- `MainActivity`（exported, singleTask, adjustResize, MAIN+LAUNCHER）
- `SchedulerForegroundService`（exported=false, `foregroundServiceType=specialUse`, 带 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="social_ai_scheduler_keepalive"`）
- `BootReceiver`（exported=true, `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED`）
- `InitializationProvider`（`tools:node="merge"`，内部 `WorkManagerInitializer` `tools:node="remove"`）
- `FileProvider`（authority=`${applicationId}.fileprovider`）

## 构建配置（app/build.gradle.kts）

### plugins

- `android.application`
- `kotlin.android`
- `kotlin.compose`
- `kotlin.serialization`
- `hilt`
- `ksp`

### defaultConfig

- `applicationId="com.trae.social"`
- `minSdk=26`
- `targetSdk=34`
- `versionCode=1`
- `versionName="0.1.0"`
- `vectorDrawables.useSupportLibrary=true`

### signingConfigs.release

- `keystore.properties` 存在时填入。

### buildTypes

- debug：`applicationIdSuffix=".debug"`、`versionNameSuffix="-debug"`
- release：
  - `isMinifyEnabled=!ciDisableMinify`
  - `isShrinkResources=!ciDisableMinify`
  - `proguardFiles getDefaultProguardFile + proguard-rules.pro`
  - `signingConfig` 回退 debug

### splits.abi

- enable
- include `arm64-v8a` / `armeabi-v7a` / `x86_64`
- `universalApk=true`

### buildFeatures

- `compose=true`
- `buildConfig=true`

### 依赖

- 10 个项目模块 + Compose + Hilt + WorkManager + 协程 + Timber + 测试（含 Hilt androidTest 基础设施）。
- 注意：`androidx.core.splashscreen` 依赖已声明但 `installSplashScreen()` 从未被调用，`themes.xml` 也未引用 `Theme.SplashScreen`（遗留/待启用项）。

## 资源

### strings.xml

- `app_name="LLM Social"`

### themes.xml

- `Theme.Social` 父主题 `android:Theme.Material.Light.NoActionBar`（values）/ `android:Theme.Material.NoActionBar`（values-night）。
- `windowBackground=@color/background`，状态栏/导航栏透明。
- `windowLightStatusBar`（values=true, values-night=false）。
- 注释说明 framework 无 `Theme.Material.DayNight.NoActionBar`，故用 values-night 限定符覆盖避免冷启动白屏闪烁。

### colors.xml

- `background=#FDFBFF`（明）/ `#000000`（夜）
- `ic_launcher_background=#3B6FE6`

### file_paths.xml

- `cache-path` `images/` 与 `shared/`
- `files-path` `.`
- `external-files-path` `.`
- `external-cache-path` `.`
- authority 为 `com.trae.social.fileprovider`
