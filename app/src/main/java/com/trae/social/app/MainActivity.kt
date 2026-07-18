package com.trae.social.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.trae.social.designsystem.theme.SocialTheme
import com.trae.social.designsystem.theme.ThemePreferences
import com.trae.social.feed.FeedScreen
import com.trae.social.onboarding.OnboardingNavHost
import com.trae.social.profile.ApiKeyManagementScreen
import com.trae.social.profile.DevOptionsScreen
import com.trae.social.profile.FollowListScreen
import com.trae.social.profile.FollowListType
import com.trae.social.profile.ProfileChatScreen
import com.trae.social.profile.ProfileScreen
import com.trae.social.profile.SettingsScreen
import com.trae.social.publish.PublishScreen
import com.trae.social.timeline.TimelineScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // #12：加载用户主题偏好（浅色/深色/跟随系统），供 SocialTheme 覆写 isSystemInDarkTheme
        ThemePreferences.initialize(this)
        // 在前台上下文（Activity 启动）初始化调度器并启动前台服务。
        // 不能放在 Application.onCreate：此时无可见 Activity，Android 12+ 会因
        // 从后台启动前台服务抛 ForegroundServiceStartNotAllowedException 导致启动即崩。
        // SchedulerInitializer 内部有幂等守卫，Activity 重建时不会重复初始化。
        SchedulerInitializer.initialize(this)
        // #146：通过生命周期观察者驱动 SessionManager，
        // - onResume → 复用 30s 内旧会话或开新会话，发 SESSION_START
        // - onPause → 记录暂停时间戳，不立即结束会话
        // - onStop 触发后 Activity 真正不可见，等下次 onResume 判定是否合并
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                sessionManager.onResume(screen = "MainActivity")
                // #146 C 修复：前台进入强制检查基础分析双阈值（事件计数/时间），
                // 达阈值则生成快照，打通"捕获→基础分析→LLM 画像"链路
                basicProfileTrigger.forceCheckOnForeground()
            }

            override fun onPause(owner: LifecycleOwner) {
                sessionManager.onPause()
            }
        })
        setContent {
            // #12：读取主题偏好覆写系统深色模式；偏好变更时此处会重组
            val darkTheme = ThemePreferences.isDarkTheme(isSystemInDarkTheme())
            SocialTheme(darkTheme = darkTheme) {
                SocialApp(
                    configRepository = configRepository,
                    userActionTracker = userActionTracker,
                    sessionManager = sessionManager,
                )
            }
        }
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
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
    val onboardingDone by produceState<Boolean?>(initialValue = null) {
        value = configRepository.isOnboardingCompleted()
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
                            scope.launch {
                                configRepository.setOnboardingCompleted(true)
                                configRepository.setOnboardingSkipped(skipped)
                                navController.navigate(AppRoutes.MAIN) {
                                    popUpTo(AppRoutes.ONBOARDING) { inclusive = true }
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
                    .drawWithContent {
                        if (canBlur) {
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
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() },
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
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() },
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
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() },
                ) {
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.PROFILE, builder = actionBuilder)
                    ProfileScreen(
                        onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) },
                        onNavigateToFollowList = { type ->
                            navController.navigate(AppRoutes.followList(type.name))
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable(
                    route = AppRoutes.SETTINGS,
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() },
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
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() },
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
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() },
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
                    enterTransition = { slideInVertically(initialOffsetY = { it }) },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { slideOutVertically(targetOffsetY = { it }) },
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
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() },
                ) { backStackEntry ->
                    // M1 修复：屏幕进入/离开埋点
                    ScreenTracking(route = AppRoutes.FOLLOW_LIST, builder = actionBuilder)
                    val typeName = backStackEntry.arguments?.getString(AppRoutes.FOLLOW_LIST_TYPE_ARG) ?: "FOLLOWING"
                    val type = runCatching { FollowListType.valueOf(typeName) }
                        .getOrElse { FollowListType.FOLLOWING }
                    FollowListScreen(
                        type = type,
                        onBack = { navController.popBackStack() },
                        // #11：账号详情页待接入，预留回调结构
                        onAccountClick = { },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable(
                    route = AppRoutes.PUBLISH,
                    enterTransition = { slideInVertically(initialOffsetY = { it }) },
                    exitTransition = { fadeOut() },
                    popExitTransition = { slideOutVertically(targetOffsetY = { it }) },
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
