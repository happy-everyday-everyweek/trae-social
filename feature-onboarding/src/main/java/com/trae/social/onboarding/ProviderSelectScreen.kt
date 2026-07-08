package com.trae.social.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography
import com.trae.social.core.data.config.LlmProvider

/**
 * 提供商选择页（SubTask 9.2）。
 *
 * 4 张卡片单选：OpenAI / Anthropic / Gemini / 自定义（OpenAI 兼容）。
 * 每卡片含色块 logo（首字母）+ 名称 + 简介，选中时以 systemBlue 边框高亮。
 *
 * @param viewModel 引导流程 ViewModel
 * @param onNext 点击"下一步"回调（导航至 KeyInputScreen）
 * @param onBack 点击返回回调
 */
@Composable
fun ProviderSelectScreen(
    viewModel: OnboardingViewModel,
    onNext: () -> Unit,
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "选择 AI 提供商",
            style = typography.title1,
            color = colors.label,
        )
        Text(
            text = "选择一个 LLM 服务驱动虚拟账号的内容生成",
            style = typography.subheadline,
            color = colors.secondaryLabel,
        )

        Spacer(Modifier.size(8.dp))

        PROVIDER_OPTIONS.forEach { option ->
            ProviderCard(
                option = option,
                selected = state.selectedProvider == option.provider,
                onClick = { viewModel.selectProvider(option.provider) },
            )
        }

        Spacer(Modifier.weight(1f))

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
                    text = "返回",
                    style = typography.body,
                    color = colors.label,
                )
            }
            ActionButton(
                text = "下一步",
                onClick = onNext,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 单个提供商卡片。
 *
 * @param option 提供商选项元数据
 * @param selected 是否选中
 * @param onClick 点击回调
 */
@Composable
private fun ProviderCard(
    option: ProviderOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val borderColor = if (selected) colors.systemBlue else colors.separator
    val borderWidth = if (selected) 2.dp else 1.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.systemBackground)
            .border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 色块 logo：背景色 + 首字母
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(option.logoColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = option.logoLetter,
                style = typography.title2.copy(color = Color.White),
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = option.name,
                style = typography.headline,
                color = colors.label,
            )
            Text(
                text = option.description,
                style = typography.footnote,
                color = colors.secondaryLabel,
            )
        }

        // 选中指示器
        if (selected) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(colors.systemBlue),
            )
        }
    }
}

/**
 * 提供商选项元数据。
 */
private data class ProviderOption(
    val provider: LlmProvider,
    val name: String,
    val description: String,
    val logoColor: Color,
    val logoLetter: String,
)

/**
 * 4 个提供商的卡片配置。
 */
private val PROVIDER_OPTIONS: List<ProviderOption> = listOf(
    ProviderOption(
        provider = LlmProvider.OPENAI,
        name = "OpenAI",
        description = "GPT 系列模型，通用能力强，生态成熟",
        logoColor = Color(0xFF10A37F),
        logoLetter = "O",
    ),
    ProviderOption(
        provider = LlmProvider.ANTHROPIC,
        name = "Anthropic",
        description = "Claude 系列模型，长文本与一致性表现出色",
        logoColor = Color(0xFFD97757),
        logoLetter = "A",
    ),
    ProviderOption(
        provider = LlmProvider.GEMINI,
        name = "Google Gemini",
        description = "Gemini 系列模型，多模态能力突出",
        logoColor = Color(0xFF4285F4),
        logoLetter = "G",
    ),
    ProviderOption(
        provider = LlmProvider.CUSTOM,
        name = "自定义（OpenAI 兼容）",
        description = "兼容 OpenAI 协议的第三方端点，需填写 Base URL",
        logoColor = Color(0xFF6B7280),
        logoLetter = "C",
    ),
)
