package com.trae.social.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
 * IMPL-13：[onCompleted] 接收 `skipped` 参数区分跳过与完成，
 * app 层据此写入 `onboardingSkipped` 标记，FeedScreen 据此展示 banner。
 *
 * #35：顶部统一展示 5 段进度指示，帮助用户感知引导流程进度。
 *
 * @param onCompleted 引导完成回调（skipped=true 表示用户跳过引导）
 * @param modifier 修饰符
 */
@Composable
fun OnboardingNavHost(
    onCompleted: (skipped: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController: NavHostController = rememberNavController()
    val viewModel: OnboardingViewModel = hiltViewModel()
    val colors = LocalSocialColors.current

    // #35：根据当前路由计算引导步骤索引（1~5），驱动顶部进度指示
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentStep = when (currentRoute) {
        OnboardingRoute.WELCOME -> 1
        OnboardingRoute.PROVIDER -> 2
        OnboardingRoute.KEY -> 3
        OnboardingRoute.TEST -> 4
        OnboardingRoute.DONE -> 5
        else -> 1
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.systemBackground)
            // IMPL-49 / #41：引导流程统一处理系统栏 inset，避免标题被状态栏/刘海遮挡，
            // 底部内容被手势条遮挡；各子屏幕不再单独硬编码 top/bottom padding。
            // #35：inset 上移到外层 Column，使进度指示与各子屏幕共享同一 inset 处理。
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // #35：5 段进度指示（1/5 ~ 5/5），位于所有引导子屏幕顶部
        OnboardingProgressIndicator(
            currentStep = currentStep,
            totalSteps = 5,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )

        NavHost(
            navController = navController,
            startDestination = OnboardingRoute.WELCOME,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(OnboardingRoute.WELCOME) {
                WelcomeScreen(
                    onStart = { navController.navigate(OnboardingRoute.PROVIDER) },
                    onSkip = {
                        // IMPL-13：跳过引导，传 skipped=true 使 app 层写入 onboardingSkipped
                        viewModel.skip(onSkipped = { onCompleted(true) })
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
                    onCompleted = { onCompleted(false) },
                )
            }
        }
    }
}

/**
 * 引导进度指示器（#35）。
 *
 * 横向排布 [totalSteps] 段细线：已完成与当前步骤为 systemBlue，未来步骤为 tertiaryBackground。
 * 整体合并为一句话义（"步骤 N / M"），TalkBack 一次朗读即可感知进度。
 */
@Composable
private fun OnboardingProgressIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    Row(
        modifier = modifier.semantics {
            contentDescription = "步骤 $currentStep / $totalSteps"
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in 1..totalSteps) {
            val reached = i <= currentStep
            val segmentColor = if (reached) colors.systemBlue else colors.tertiaryBackground
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(segmentColor),
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
