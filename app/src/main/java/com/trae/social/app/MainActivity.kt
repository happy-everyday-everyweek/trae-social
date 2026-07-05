package com.trae.social.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trae.social.app.ui.SocialBottomBar
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.designsystem.theme.SocialTheme
import com.trae.social.feed.FeedScreen
import com.trae.social.onboarding.OnboardingNavHost
import com.trae.social.profile.ProfileScreen
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
                        onCompleted = {
                            // 引导完成：写入标记并切换至主框架
                            scope.launch {
                                configRepository.setOnboardingCompleted(true)
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

    // publish 为全屏路由，隐藏底部栏使其覆盖整屏
    val showBottomBar = currentRoute != "publish"

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
                FeedScreen(modifier = Modifier.fillMaxSize().padding(innerPadding))
            }
            composable(
                route = "timeline",
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() },
            ) {
                TimelineScreen(modifier = Modifier.fillMaxSize().padding(innerPadding))
            }
            composable(
                route = "profile",
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() },
            ) {
                ProfileScreen(modifier = Modifier.fillMaxSize().padding(innerPadding))
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
    }
}
