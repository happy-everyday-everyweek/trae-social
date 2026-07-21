package com.trae.social.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.components.LoadingShimmer
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 连通性测试页（SubTask 9.4）。
 *
 * 状态展示：
 * - Idle：展示"测试连接"按钮，提示用户发起测试
 * - Loading：LoadingShimmer + "正在测试连接..."
 * - Success：绿色对勾 + "连接成功" + "完成"按钮
 * - Error：红色错误图标 + 具体原因 + "重试"/"返回修改"按钮
 *
 * @param viewModel 引导流程 ViewModel
 * @param onComplete 测试成功后点击"完成"回调（保存配置 + 冷启动 + 进入主界面）
 * @param onBack 点击"返回修改"回调（回到 KeyInputScreen）
 */
@Composable
fun ConnectionTestScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.systemBackground)
            .padding(horizontal = 24.dp)
            // IMPL-49 / #41：顶部/底部系统栏 inset 由 OnboardingNavHost 统一处理
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "连通性测试",
            style = typography.title1,
            color = colors.label,
        )
        Text(
            text = "向 ${state.selectedProvider.displayName} 发起一次 ping 请求，验证 API Key 与端点配置是否可用",
            style = typography.subheadline,
            color = colors.secondaryLabel,
        )

        Spacer(Modifier.size(24.dp))

        // #161：四种测试状态用 AnimatedContent 包裹，fadeIn/fadeOut tween(200) 平滑过渡，
        // 避免状态切换硬切造成布局跳动与突兀感
        AnimatedContent(
            targetState = state.testStatus,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
            label = "testStatus",
        ) { status ->
            when (status) {
                is OnboardingViewModel.TestStatus.Idle -> IdleState(
                    onTest = { viewModel.testConnection() },
                )
                is OnboardingViewModel.TestStatus.Loading -> LoadingState()
                is OnboardingViewModel.TestStatus.Success -> SuccessState(
                    isSaving = state.isSaving,
                    saveError = state.saveError,
                    onComplete = onComplete,
                    onRetry = onComplete,
                )
                is OnboardingViewModel.TestStatus.Error -> ErrorState(
                    message = status.message,
                    onRetry = { viewModel.testConnection() },
                    onBack = onBack,
                )
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

/**
 * 空闲态：展示"测试连接"按钮。
 */
@Composable
private fun IdleState(onTest: () -> Unit) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(colors.secondaryBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "?",
                style = typography.largeTitle,
                color = colors.tertiaryLabel,
            )
        }
        Text(
            text = "尚未测试",
            style = typography.body,
            color = colors.secondaryLabel,
        )
        ActionButton(
            text = "测试连接",
            onClick = onTest,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 测试中：LoadingShimmer + 文案。
 */
@Composable
private fun LoadingState() {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadingShimmer(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape),
            cornerRadius = 40.dp,
        )
        Text(
            text = "正在测试连接...",
            style = typography.body,
            color = colors.label,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        LoadingShimmer(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
        )
    }
}

/**
 * 成功态：绿色对勾 + "完成"按钮。
 *
 * 主 review 第 1 轮 M1 修复：增加保存中（[isSaving]）禁用按钮 + 保存失败
 * （[saveError] 非 null）展示错误原因 + 按钮文案切换为"重试"。
 * 原实现 saveAndComplete 失败后仅 isSaving=false，UI 无任何失败反馈，
 * 用户不知道保存没成功，按钮仍可点但点击无响应（saveAndComplete 内部已写入端点）。
 */
@Composable
private fun SuccessState(
    isSaving: Boolean,
    saveError: String?,
    onComplete: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(colors.systemGreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "成功",
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = "连接成功",
            style = typography.title2,
            color = colors.label,
        )
        Text(
            text = "API Key 与端点配置可用，可完成引导并进入主界面",
            style = typography.subheadline,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        // M1 修复：保存失败时展示具体错误原因，让用户知道为何没进入主界面
        if (saveError != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.secondaryBackground)
                    .padding(16.dp),
            ) {
                Text(
                    text = "保存失败：$saveError",
                    style = typography.footnote,
                    color = colors.systemRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        ActionButton(
            // 保存失败后按钮文案切换为"重试"，点击触发同一个 onComplete/onRetry 回调
            // （均会调 viewModel.saveAndComplete 重新尝试保存）
            text = if (saveError != null) "重试" else "完成",
            // 保存中禁用按钮防止重复触发；M1 前 isSaving 期间按钮仍可点
            onClick = if (saveError != null) onRetry else onComplete,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 失败态：红色错误图标 + 原因 + "重试"/"返回修改"按钮。
 */
@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(colors.systemRed),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "失败",
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = "连接失败",
            style = typography.title2,
            color = colors.systemRed,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.secondaryBackground)
                .padding(16.dp),
        ) {
            Text(
                text = message,
                style = typography.footnote,
                color = colors.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = CircleShape,
            ) {
                Text(
                    text = "返回修改",
                    style = typography.body,
                    color = colors.label,
                )
            }
            ActionButton(
                text = "重试",
                onClick = onRetry,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
