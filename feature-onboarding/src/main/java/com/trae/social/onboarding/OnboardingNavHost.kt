package com.trae.social.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trae.social.designsystem.theme.LocalSocialColors

/**
 * 引导流程导航宿主（SubTask 9.5）。
 *
 * 内嵌 NavHost 承载 5 个路由：
 * - welcome：欢迎页（含免责声明 RISK-12）
 * - provider：提供商选择页
 * - key：API Key / Base URL / 模型输入页
 * - test：连通性测试页（调用 LlmClient.ping）
 * - done：完成页（保存配置 + 触发冷启动填充 RISK-14）
 *
 * 流程结束时调用 [onCompleted] 通知上层切换至主框架。
 * 跳过逻辑：WelcomeScreen 的"稍后"→ [OnboardingViewModel.skip] → [onCompleted]。
 *
 * @param onCompleted 引导完成回调（由 app 模块标记完成并跳转主界面）
 * @param modifier 修饰符
 */
@Composable
fun OnboardingNavHost(
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController: NavHostController = rememberNavController()
    val viewModel: OnboardingViewModel = hiltViewModel()
    val colors = LocalSocialColors.current

    NavHost(
        navController = navController,
        startDestination = OnboardingRoute.WELCOME,
        modifier = modifier
            .fillMaxSize()
            .background(colors.systemBackground),
    ) {
        composable(OnboardingRoute.WELCOME) {
            WelcomeScreen(
                onStart = { navController.navigate(OnboardingRoute.PROVIDER) },
                onSkip = {
                    // 跳过引导：不写入 LLM 配置，直接进入主界面
                    // onboarding_completed 标记由 app 层 onCompleted 回调统一写入
                    viewModel.skip(onSkipped = onCompleted)
                },
            )
        }
        composable(OnboardingRoute.PROVIDER) {
            ProviderSelectScreen(
                viewModel = viewModel,
                onNext = { navController.navigate(OnboardingRoute.KEY) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(OnboardingRoute.KEY) {
            KeyInputScreen(
                viewModel = viewModel,
                onTest = { navController.navigate(OnboardingRoute.TEST) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(OnboardingRoute.TEST) {
            ConnectionTestScreen(
                viewModel = viewModel,
                onComplete = {
                    // 测试成功后点击"完成"：保存配置 + 触发冷启动，然后进入 done 页
                    viewModel.saveAndComplete(onSaved = {
                        navController.navigate(OnboardingRoute.DONE) {
                            popUpTo(OnboardingRoute.KEY) { inclusive = true }
                        }
                    })
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(OnboardingRoute.DONE) {
            DoneScreen(
                onCompleted = onCompleted,
            )
        }
    }
}

/**
 * 引导流程路由常量。
 */
internal object OnboardingRoute {
    const val WELCOME = "welcome"
    const val PROVIDER = "provider"
    const val KEY = "key"
    const val TEST = "test"
    const val DONE = "done"
}
