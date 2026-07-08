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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trae.social.app.ui.SocialBottomBar
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.designsystem.components.provideIsScrolling
import com.trae.social.designsystem.theme.SocialTheme
import com.trae.social.feed.FeedScreen
import com.trae.social.onboarding.OnboardingNavHost
import com.trae.social.profile.ApiKeyManagementScreen
import com.trae.social.profile.DevOptionsScreen
import com.trae.social.profile.FollowListScreen
import com.trae.social.profile.FollowListType
import com.trae.social.profile.ProfileScreen
import com.trae.social.profile.SettingsScreen
import com.trae.social.publish.PublishScreen
import com.trae.social.timeline.TimelineScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SocialTheme {
                SocialApp(configRepository = configRepository)
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
private fun SocialApp(configRepository: ConfigRepository) {
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
            val startDestination = if (done) "main" else "onboarding"
            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                composable("onboarding") {
                    OnboardingNavHost(
                        onCompleted = { skipped ->
                            // IMPL-13：区分跳过与完成，写入对应标记并切换至主框架
                            scope.launch {
                                configRepository.setOnboardingCompleted(true)
                                configRepository.setOnboardingSkipped(skipped)
                                navController.navigate("main") {
                                    popUpTo("onboarding") { inclusive = true }
                                }
                            }
                        },
                    )
                }
                composable("main") {
                    MainScaffold()
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
private fun MainScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 仅在三个主 Tab 显示底部栏；settings/devoptions/apikey/followlist/publish 为全屏路由
    val showBottomBar = currentRoute in setOf("feed", "timeline", "profile")

    // IMPL-33：由 feed/timeline 的 LazyColumn 派生滚动状态，供 GlassBlurContainer 减半模糊半径
    var isScrolling by remember { mutableStateOf(false) }

    // 用 provideIsScrolling 包裹整个 Scaffold，使 bottomBar 内的 GlassBlurContainer 可读取
    provideIsScrolling(isScrolling) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    SocialBottomBar(
                        currentRoute = currentRoute,
                        onTabSelected = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onPublishClick = {
                            navController.navigate("publish") {
                                launchSingleTop = true
                            }
                        },
                    )
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "feed",
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(
                    route = "feed",
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() },
                ) {
                    FeedScreen(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        onScrollingChange = { isScrolling = it },
                    )
                }
                composable(
                    route = "timeline",
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() },
                ) {
                    TimelineScreen(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        onScrollingChange = { isScrolling = it },
                    )
                }
            composable(
                route = "profile",
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() },
            ) {
                ProfileScreen(
                    onNavigateToSettings = { navController.navigate("settings") },
                    onNavigateToFollowList = { type ->
                        navController.navigate("followlist/${type.name}")
                    },
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            }
            composable(
                route = "settings",
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() },
            ) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToApiKey = { navController.navigate("apikey") },
                    onNavigateToDevOptions = { navController.navigate("devoptions") },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(
                route = "apikey",
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() },
            ) {
                ApiKeyManagementScreen(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(
                route = "devoptions",
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() },
            ) {
                DevOptionsScreen(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(
                route = "followlist/{type}",
                arguments = listOf(navArgument("type") { type = NavType.StringType }),
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() },
            ) { backStackEntry ->
                val typeName = backStackEntry.arguments?.getString("type") ?: "FOLLOWING"
                val type = runCatching { FollowListType.valueOf(typeName) }
                    .getOrElse { FollowListType.FOLLOWING }
                FollowListScreen(
                    type = type,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(
                route = "publish",
                enterTransition = { slideInVertically(initialOffsetY = { it }) },
                exitTransition = { fadeOut() },
                popExitTransition = { slideOutVertically(targetOffsetY = { it }) },
            ) {
                // publish 全屏，不应用底部 padding 以覆盖整屏
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
