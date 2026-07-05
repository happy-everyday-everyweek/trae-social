package com.trae.social.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 引导流程导航宿主。
 *
 * 当前为占位实现，直接渲染 [OnboardingScreen]。
 * Task 9 将填充完整引导流程（多步骤页面 + NavHost 内嵌导航），
 * 流程结束时调用 [onCompleted] 通知上层切换至主框架。
 *
 * @param onCompleted 引导完成回调
 * @param modifier 修饰符
 */
@Composable
fun OnboardingNavHost(
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScreen(modifier = modifier)
}
