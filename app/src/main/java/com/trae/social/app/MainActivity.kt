package com.trae.social.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trae.social.app.ui.AppRoutes
import com.trae.social.app.ui.SocialBottomBar
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.profiling.capture.SessionManager
import com.trae.social.core.profiling.capture.UserActionEventBuilder
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.core.profiling.analysis.BasicProfileTrigger
import com.trae.social.core.scheduler.SchedulerInitializer
import com.trae.social.designsystem.components.GlassBlurTier
import com.trae.social.designsystem.components.provideIsScrolling
import com.trae.social.designsystem.components.rememberGlassBlurTier
import com.trae.social.designsystem.theme.LocalReduceMotion
import com.trae.social.designsystem.theme.SocialTheme
import com.trae.social.designsystem.theme.ThemePreferences
import com.trae.social.feed.FeedScreen
import com.trae.social.onboarding.OnboardingNavHost
import com.trae.social.profile.ApiKeyManagementScreen
import com.trae.social.profile.DevOptionsScreen
import com.trae.social.profile.FollowListScreen
import com.trae.social.profile.ProfileChatScreen
import com.trae.social.profile.ProfileScreen
import com.trae.social.profile.SettingsScreen
import com.trae.social.publish.PublishScreen
import com.trae.social.timeline.TimelineScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import javax.inject.Inject

/**
 * 应用主入口 Activity（单 Activity 架构）。
 *
 * 职责：
 * 1. 读取 [ConfigRepository.isOnboardingCompleted] 决定起始路由
 * 2. 顶层 NavHost 区分引导（onboarding）与主框架（main）
 * 3. 主框架 [MainScaffold] 内嵌 NavHost 承载 feed/timeline/profile/publish 路由
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var configRepository: ConfigRepository

    // #146：会话管理（onResume/onPause 触发 SESSION_START/END 埋点，30s 超时合并）
    @Inject
    lateinit var sessionManager: SessionManager

    // #146：用户行为埋点 Tracker（M1/M2 修复：NavHost 屏幕进入/离开 + Tab 切换埋点）
    @Inject
    lateinit var userActionTracker: UserActionTracker

    // #146：基础分析触发器（C 修复：前台进入时强制检查双阈值，触发基础画像快照生成，
    // 否则 BasicProfileTrigger 无调用方 → 快照表恒空 → UserProfileWorker 永远 no_snapshot 短路 → 第2/3/4层全断链）
    @Inject
    lateinit var basicProfileTrigger: BasicProfileTrigger

    // #210：singleTask 模式下二次启动走 onNewIntent 而非 onCreate，
    // 通过该 StateFlow 将新 Intent 透传给 Compose 层（NavHost），未来接入深链时观察此流即可路由。
    private val _newIntentFlow = MutableStateFlow<Intent?>(null)
    val newIntentFlow: StateFlow<Intent?> = _newIntentFlow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // #12：加载用户主题偏好（浅色/深色/跟随系统），供 SocialTheme 覆写 isSystemInDarkTheme
        // #39：包裹防御性 try/catch，主题偏好初始化失败不应阻塞应用启动
        try {
            ThemePreferences.initialize(this)
        } catch (t: Throwable) {
            Timber.e(t, "ThemePreferences.initialize 失败，回退默认主题")
        }
        // 在前台上下文（Activity 启动）初始化调度器并启动前台服务。
        // 不能放在 Application.onCreate：此时无可见 Activity，Android 12+ 会因
        // 从后台启动前台服务抛 ForegroundServiceStartNotAllowedException 导致启动即崩。
        // SchedulerInitializer 内部有幂等守卫，Activity 重建时不会重复初始化。
        // #39：包裹防御性 try/catch，SchedulerInitializer 内部已有异常处理但
        // EntryPointAccessors.fromApplication 等步骤可能抛 Hilt 未初始化异常，避免启动即崩
        try {
            SchedulerInitializer.initialize(this)
        } catch (t: Throwable) {
            Timber.e(t, "SchedulerInitializer.initialize 失败，调度任务将由 WorkManager 兜底")
        }
        // #146：通过生命周期观察者驱动 SessionManager，
        // - onResume → 复用 30s 内旧会话或开新会话，发 SESSION_START
        // - onPause → 记录暂停时间戳，不立即结束会话
        // - onStop 触发后 Activity 真正不可见，等下次 onResume 判定是否合并
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                // #39：包裹生命周期回调中的埋点/分析调用，避免异常传播导致 Activity 崩溃
                try {
                    sessionManager.onResume(screen = "MainActivity")
                } catch (t: Throwable) {
                    Timber.e(t, "sessionManager.onResume 失败")
                }
                // #146 C 修复：前台进入强制检查基础分析双阈值（事件计数/时间），
                // 达阈值则生成快照，打通"捕获→基础分析→LLM 画像"链路
                try {
                    basicProfileTrigger.forceCheckOnForeground()
                } catch (t: Throwable) {
                    Timber.e(t, "basicProfileTrigger.forceCheckOnForeground 失败")
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                // #39：包裹 onPause 中的会话记录，避免异常传播
                try {
                    sessionManager.onPause()
                } catch (t: Throwable) {
                    Timber.e(t, "sessionManager.onPause 失败")
                }
            }
        })
        // #210：onCreate 路径下首次 Intent 也写入 newIntentFlow，保证 Compose 层观察到的初始值一致
        _newIntentFlow.value = intent
        setContent {
            // #12：读取主题偏好覆写系统深色模式；偏好变更时此处会重组
            val darkTheme = ThemePreferences.isDarkTheme(isSystemInDarkTheme())
            SocialTheme(darkTheme = darkTheme) {
                SocialApp(
                    configRepository = configRepository,
                    userActionTracker = userActionTracker,
                    sessionManager = sessionManager,
                    newIntentFlow = newIntentFlow,
                )
            }
        }
    }

    // #210：singleTask 模式下 Activity 已存活时再次被启动（如从桌面图标二次点击、
    // 通知 PendingIntent、外部跳转），系统调用 onNewIntent 而非 onCreate。
    // 必须调用 setIntent 让后续 getIntent() 拿到最新 Intent，并将 Intent 推入
    // newIntentFlow 供 Compose 层（NavHost）做深链路由。当前无深链 intent-filter，
    // 此 hook 为未来接入深链/通知跳转预留。
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        _newIntentFlow.value = intent
    }
}

/**
 * 应用根导航：根据引导完成状态选择起始路由。
 *
 * - 引导未完成：startDestination = "onboarding" -> [OnboardingNavHost]
 * - 引导已完成：startDestination = "main" -> [MainScaffold]
 */
@Composable
private fun SocialApp(
    configRepository: ConfigRepository,
    userActionTracker: UserActionTracker,
    sessionManager: SessionManager,
    // #210：MainActivity.onNewIntent 透传的 Intent 流，未来接入深链时观察此流做路由
    newIntentFlow: StateFlow<Intent?>,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // #210 / #293：观察 newIntentFlow 做深链路由。
    // 已消费去重：StateFlow 旋转后会重放最后一个值，用 remembered Set 记录已处理的
    // intent.data 字符串，避免旋转后重复 navigate 到同一页面。
    val consumedDeepLinks = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(newIntentFlow) {
        newIntentFlow.collect { intent ->
            if (intent != null) {
                val dataString = intent.dataString
                if (dataString != null && consumedDeepLinks[dataString] != true) {
                    consumedDeepLinks[dataString] = true
                    val route = intent.data?.lastPathSegment?.lowercase()
                    when (route) {
                        "feed", "timeline", "profile", "publish",
                        "settings", "apikey", "devoptions", "profile_chat" -> {
                            navController.navigate(route)
                        }
                        null -> Unit
                        else -> Timber.w("未知深链路径: %s", dataString)
                    }
                }
            }
        }
    }

    // IMPL-46：Android 13+ 运行时申请 POST_NOTIFICATIONS 权限，
    // 确保调度前台服务的常驻通知可见
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // 用户授予或拒绝均不阻塞应用流程，调度服务在授权后显示通知
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // 一次性读取引导完成状态；未读到前显示空白，避免起始路由闪烁
    // #179：produceState 内用 runCatching 包裹 DataStore 读取，异常时回退为 false
    // 保证 value 一定被赋值，避免异常导致 produceState 协程被取消后 value 永久停留 null
    // 进而 UI 白屏。runCatching 会吞 CancellationException，故显式 rethrow。
    val onboardingDone by produceState<Boolean?>(initialValue = null) {
        value = try {
            configRepository.isOnboardingCompleted()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.e(t, "读取引导完成状态失败，回退为未完成")
            false
        }
    }

    when (val done = onboardingDone) {
        null -> {
            // 加载中占位，DataStore 读取通常极快
            Box(Modifier.fillMaxSize())
        }
        else -> {
            val startDestination = if (done) AppRoutes.MAIN else AppRoutes.ONBOARDING
            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                composable(AppRoutes.ONBOARDING) {
                    OnboardingNavHost(
                        onCompleted = { skipped ->
                            // IMPL-13：区分跳过与完成，写入对应标记并切换至主框架
                            // #179：先 navigate 再异步写入 DataStore，写入失败仅记日志不阻塞
                            // UI 流转；原顺序若 setOnboardingCompleted 抛异常则 navigate 不执行
                            // 用户卡死在引导页且状态不一致
                            scope.launch {
                                navController.navigate(AppRoutes.MAIN) {
                                    popUpTo(AppRoutes.ONBOARDING) { inclusive = true }
                                }
                                try {
                                    configRepository.setOnboardingCompleted(true)
                                    configRepository.setOnboardingSkipped(skipped)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    Timber.e(t, "写入引导完成状态失败，UI 已切换但状态未持久化")
                                }
                            }
                        },
                    )
                }
                composable(AppRoutes.MAIN) {
                    MainScaffold(
                        userActionTracker = userActionTracker,
                        sessionManager = sessionManager,
                    )
                }
            }
        }
    }
}

/**
 * 主框架：底部磨砂玻璃导航栏 + 内嵌 NavHost（feed/timeline/profile/publish）。
 *
 * - Tab 切换保持状态（saveState/restoreState + launchSingleTop）
 * - publish 为全屏路由，从底部滑入，显示时隐藏底部栏
 * - Tab 间切换淡入淡出
 */
@Composable
private fun MainScaffold(
    userActionTracker: UserActionTracker,
    sessionManager: SessionManager,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // M1/M2 修复：构造埋点 Builder，session 由 SessionManager 提供。
    // Tracker/SessionManager 均为 Hilt 单例，remember 一次即可；emit 内部走 trackNow（fire-and-forget，非 suspend）。
    val actionBuilder = remember(userActionTracker, sessionManager) {
        UserActionEventBuilder(userActionTracker) { sessionManager.currentSessionId() ?: "" }
    }

    // 仅在三个主 Tab 显示底部栏；settings/devoptions/apikey/followlist/publish 为全屏路由
    val showBottomBar = currentRoute in setOf(AppRoutes.FEED, AppRoutes.TIMELINE, AppRoutes.PROFILE)

    // IMPL-33：由 feed/timeline 的 LazyColumn 派生滚动状态，供 GlassBlurContainer 减半模糊半径
    var isScrolling by remember { mutableStateOf(false) }

    // #198：路由切换时重置 isScrolling，避免上一个 Tab 的滚动状态残留导致底栏
    // 模糊半径短暂减半（Feed 滚动中切到 Timeline，isScrolling 在 Timeline 首帧仍为 true）
    LaunchedEffect(currentRoute) {
        isScrolling = false
    }

    // #154：减弱动效模式下，路由切换降级为瞬时过渡，避免前庭敏感用户不适。
    // 仍保留 color/opacity 过渡感（None 等同于瞬时切换，符合 reduce-motion 规范）。
    // 类型必须为带 receiver 的函数类型以匹配 composable() 的 enterTransition 参数签名。
    val reduceMotion = LocalReduceMotion.current
    val tabEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduceMotion) EnterTransition.None else fadeIn()
    }
    val tabExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduceMotion) ExitTransition.None else fadeOut()
    }
    // 全屏路由的纵向滑动过渡（OPEN_PROFILE_CHAT / PUBLISH）
    val slideEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduceMotion) EnterTransition.None else slideInVertically(initialOffsetY = { it })
    }
    val slideExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduceMotion) ExitTransition.None else slideOutVertically(targetOffsetY = { it })
    }

    // #2：可记录的 GraphicsLayer，用于捕获 NavHost 内容并在底栏中作为模糊背景重绘，
    // 实现"真正模糊背后内容"的毛玻璃效果（替代原先仅模糊纯色 tint 的伪效果）。
    val backgroundLayer = rememberGraphicsLayer()
    // 内容区与底栏的实测高度（px），用于计算背景图层的平移偏移：
    // 偏移 = 底栏高度 - 内容高度，使内容底部条带对齐到底栏区域，
    // 形成内容"延伸到栏后并被磨砂"的视觉。
    var contentHeightPx by remember { mutableStateOf(0f) }
    var barHeightPx by remember { mutableStateOf(0f) }

    // 是否可执行真实模糊：API31+ 且非低端机降级（与 GlassBlurContainer 的判定保持一致）。
    // 不可模糊时跳过 backgroundLayer.record，避免低端机/API<31 每帧无效录制的 GPU 开销。
    val blurTier = rememberGlassBlurTier()
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        blurTier != GlassBlurTier.LOW
    // 高度未测量完成前（首帧 contentHeightPx/barHeightPx 均为 0）不传背景图层，
    // 避免偏移 = 0 - 0 = 0 导致模糊到内容顶部而非底部条带的首帧错位。
    val backgroundLayerReady = contentHeightPx > 0f && barHeightPx > 0f

    // #240：用 rememberUpdatedState 持有高频变化的 currentRoute，使 onTabSelected/onPublishClick
    // lambda 仅依赖稳定引用（navController/actionBuilder），重组时不产生新实例，让 SocialBottomBar
    // 与其子组件 TabItem/PublishButton 不再因 lambda 参数不稳定而被迫 skip 失效。
    val currentRouteState = rememberUpdatedState(currentRoute)

    // #240：lambda 提升至 remember，避免 isScrolling/contentHeightPx/barHeightPx 等高频翻转状态
    // 触发 MainScaffold 重组时每次 new lambda 实例破坏子组件 skip。
    val onTabSelected = remember(navController, actionBuilder) {
        { route: String ->
            val fromRoute = currentRouteState.value
            // M2 修复：Tab 切换埋点（Feed/Timeline/Profile 间切换，带 from/to）
            if (route != fromRoute) {
                actionBuilder.emit(
                    type = UserActionType.TAB_SWITCH,
                    screen = route,
                    extra = mapOf(
                        "from" to JsonPrimitive(fromRoute ?: "unknown"),
                        "to" to JsonPrimitive(route),
                    ),
                )
            }
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val onPublishClick = remember(navController) {
        {
            navController.navigate(AppRoutes.PUBLISH) {
                launchSingleTop = true
            }
        }
    }

    // 用 provideIsScrolling 包裹整个 Scaffold，使 bottomBar 内的 GlassBlurContainer 可读取
    provideIsScrolling(isScrolling) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    SocialBottomBar(
                        currentRoute = currentRoute,
                        onTabSelected = onTabSelected,
                        onPublishClick = onPublishClick,
                        // #2：将捕获的内容图层及其平移偏移透传给底栏。
                        // 高度未测量完成前传 null，回退为纯色半透明，避免首帧偏移错位。
                        backgroundLayer = if (backgroundLayerReady) backgroundLayer else null,
                        backgroundLayerOffsetY = barHeightPx - contentHeightPx,
                        modifier = Modifier.onGloballyPositioned { coords ->
                            barHeightPx = coords.size.height.toFloat()
                        },
                    )
                }
            },
        ) { innerPadding ->
            // #45：innerPadding 统一在 NavHost 容器层应用，避免内容区覆盖底栏区域。
            // 各子屏幕不再单独 padding；publish 全屏路由时 showBottomBar=false，
            // innerPadding.bottom 仅含系统导航栏 inset，publish 内部自行处理状态栏 inset。
            NavHost(
                navController = navController,
                startDestination = AppRoutes.FEED,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .onGloballyPositioned { coords ->
                        contentHeightPx = coords.size.height.toFloat()
                    }
                    // #2：将内容绘制指令记录到 backgroundLayer（供底栏模糊重绘），
                    // 同时正常绘制内容本身，保证内容区可见性不变。
                    // 不可模糊时跳过 record，避免低端机/API<31 每帧无效录制的开销。
                    // #181：底栏隐藏时（settings/devoptions/apikey/followlist/publish 等全屏路由）
                    // backgroundLayer 无消费者，跳过 record 避免无效 GPU 录制开销。
                    .drawWithContent {
                        if (canBlur && showBottomBar) {
                            backgroundLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(backgroundLayer)
                        } else {
                            drawContent()
                        }
                    },
            ) {
                composable(
                    route = AppRoutes.FEED,
                    enterTransition = tabEnterTransition,
                    exitTransition = tabExitTransition,
                    popEnterTransition = tabEnterTransition,
                    popExitTransition = tabExitTransition,
                ) {
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.FEED, builder = actionBuilder)
                    FeedScreen(
                        modifier = Modifier.fillMaxSize(),
                        onScrollingChange = { isScrolling = it },
                        onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) },
                    )
                }
                composable(
                    route = AppRoutes.TIMELINE,
                    enterTransition = tabEnterTransition,
                    exitTransition = tabExitTransition,
                    popEnterTransition = tabEnterTransition,
                    popExitTransition = tabExitTransition,
                ) {
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.TIMELINE, builder = actionBuilder)
                    TimelineScreen(
                        modifier = Modifier.fillMaxSize(),
                        onPublishClick = { navController.navigate(AppRoutes.PUBLISH) },
                        onScrollingChange = { isScrolling = it },
                    )
                }
                composable(
                    route = AppRoutes.PROFILE,
                    enterTransition = tabEnterTransition,
                    exitTransition = tabExitTransition,
                    popEnterTransition = tabEnterTransition,
                    popExitTransition = tabExitTransition,
                ) {
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.PROFILE, builder = actionBuilder)
                    ProfileScreen(
                        onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) },
                        onNavigateToFollowList = { type ->
                            navController.navigate(AppRoutes.followList(type))
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable(
                    route = AppRoutes.SETTINGS,
                    enterTransition = tabEnterTransition,
                    exitTransition = tabExitTransition,
                    popEnterTransition = tabEnterTransition,
                    popExitTransition = tabExitTransition,
                ) {
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.SETTINGS, builder = actionBuilder)
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToApiKey = { navController.navigate(AppRoutes.API_KEY) },
                        onNavigateToDevOptions = { navController.navigate(AppRoutes.DEV_OPTIONS) },
                        // #137：个人主页入口，弹出 Settings 回到 Profile
                        onNavigateToProfile = {
                            navController.popBackStack(AppRoutes.PROFILE, inclusive = false)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable(
                    route = AppRoutes.API_KEY,
                    enterTransition = tabEnterTransition,
                    exitTransition = tabExitTransition,
                    popEnterTransition = tabEnterTransition,
                    popExitTransition = tabExitTransition,
                ) {
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.API_KEY, builder = actionBuilder)
                    ApiKeyManagementScreen(
                        onBack = { navController.popBackStack() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable(
                    route = AppRoutes.DEV_OPTIONS,
                    enterTransition = tabEnterTransition,
                    exitTransition = tabExitTransition,
                    popEnterTransition = tabEnterTransition,
                    popExitTransition = tabExitTransition,
                ) {
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.DEV_OPTIONS, builder = actionBuilder)
                    DevOptionsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToProfileChat = { navController.navigate(AppRoutes.OPEN_PROFILE_CHAT) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable(
                    route = AppRoutes.OPEN_PROFILE_CHAT,
                    enterTransition = slideEnterTransition,
                    exitTransition = tabExitTransition,
                    popEnterTransition = tabEnterTransition,
                    popExitTransition = slideExitTransition,
                ) {
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.OPEN_PROFILE_CHAT, builder = actionBuilder)
                    // #146：画像对话页（用户反馈智能体）
                    ProfileChatScreen(
                        onBack = { navController.popBackStack() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable(
                    route = AppRoutes.FOLLOW_LIST,
                    arguments = listOf(navArgument(AppRoutes.FOLLOW_LIST_TYPE_ARG) { type = NavType.StringType }),
                    enterTransition = tabEnterTransition,
                    exitTransition = tabExitTransition,
                    popEnterTransition = tabEnterTransition,
                    popExitTransition = tabExitTransition,
                ) { backStackEntry ->
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.FOLLOW_LIST, builder = actionBuilder)
                    val typeName = backStackEntry.arguments?.getString(AppRoutes.FOLLOW_LIST_TYPE_ARG)
                    val type = AppRoutes.decodeFollowListType(typeName)
                    FollowListScreen(
                        type = type,
                        onBack = { navController.popBackStack() },
                        // #11：点击账号项导航至账号详情路由
                        onAccountClick = { account ->
                            navController.navigate(AppRoutes.accountDetail(account.id))
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable(
                    route = AppRoutes.ACCOUNT_DETAIL,
                    arguments = listOf(navArgument(AppRoutes.ACCOUNT_DETAIL_ID_ARG) { type = NavType.StringType }),
                    enterTransition = tabEnterTransition,
                    exitTransition = tabExitTransition,
                    popEnterTransition = tabEnterTransition,
                    popExitTransition = tabExitTransition,
                ) {
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.ACCOUNT_DETAIL, builder = actionBuilder)
                    // #11：账号详情页。accountId 由 navArgument 注入 ProfileViewModel 的
                    // SavedStateHandle，ProfileViewModel.targetAccountId 据此加载目标账号资料。
                    // onBack 显示返回箭头；isSelfProfile=false 时 ProfileScreen 隐藏设置入口、
                    // 推荐关注入口与 LIKES Tab。
                    ProfileScreen(
                        onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) },
                        onNavigateToFollowList = { type ->
                            navController.navigate(AppRoutes.followList(type))
                        },
                        modifier = Modifier.fillMaxSize(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = AppRoutes.PUBLISH,
                    // #159：进入/退出/popEnter/popExit 四方向均沿垂直路径，符合空间一致性
                    // （原 exitTransition=fadeOut 与进入 slideInVertically 路径不一致）
                    enterTransition = slideEnterTransition,
                    exitTransition = slideExitTransition,
                    popEnterTransition = slideEnterTransition,
                    popExitTransition = slideExitTransition,
                ) {
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.PUBLISH, builder = actionBuilder)
                    // #45：publish 全屏覆盖，NavHost 已统一应用 innerPadding；
                    // showBottomBar=false 时 innerPadding.bottom 仅含系统导航栏 inset，
                    // publish 内部自行处理状态栏 inset。
                    PublishScreen(
                        modifier = Modifier.fillMaxSize(),
                        onPublished = { navController.popBackStack() },
                        onClose = { navController.popBackStack() },
                    )
                }
            }
        } // Scaffold
    } // provideIsScrolling
}

/**
 * M1 修复：屏幕进入/离开埋点辅助 Composable。
 *
 * 进入组合时发 SCREEN_ENTER，离开组合（onDispose）时发 SCREEN_LEAVE。
 * Navigation Compose 切换目的地时旧目的地离开组合、新目的地进入组合，
 * 因此可正确捕获屏幕级进入/离开事件（含 Tab saveState/restoreState 切换）。
 *
 * emit 内部走 [UserActionTracker.trackNow]（fire-and-forget，非 suspend），
 * 可直接在 DisposableEffect 中调用；Tracker 自身有 1s 去重窗口防 Compose 重组重复触发。
 *
 * @param route 当前屏幕路由名（作为 screen 参数）
 * @param builder 事件构建器（由 [MainScaffold] 通过 Hilt 单例 Tracker + SessionManager 构造）
 */
@Composable
private fun ScreenTracking(
    route: String,
    builder: UserActionEventBuilder,
) {
    DisposableEffect(route, builder) {
        builder.emit(UserActionType.SCREEN_ENTER, screen = route)
        onDispose {
            builder.emit(UserActionType.SCREEN_LEAVE, screen = route)
        }
    }
}
