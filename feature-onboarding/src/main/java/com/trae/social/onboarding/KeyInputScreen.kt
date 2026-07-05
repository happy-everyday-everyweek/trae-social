package com.trae.social.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography
import java.net.URL

/**
 * API Key 与 Base URL 输入页（SubTask 9.3）。
 *
 * - API Key：默认密码模式，可切换显示
 * - Base URL：按所选提供商预填默认值
 * - 模型名：按所选提供商预填推荐值
 * - 输入校验：Key 非空、URL 合法（http/https）
 *
 * @param viewModel 引导流程 ViewModel
 * @param onTest 点击"测试连接"回调（导航至 ConnectionTestScreen）
 * @param onBack 点击返回回调
 */
@Composable
fun KeyInputScreen(
    viewModel: OnboardingViewModel,
    onTest: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    var showKey by remember { mutableStateOf(false) }
    var keyTouched by remember { mutableStateOf(false) }
    var urlTouched by remember { mutableStateOf(false) }

    val keyError = keyTouched && state.apiKey.isBlank()
    val urlError = urlTouched && state.baseUrl.isNotBlank() && !isHttpUrl(state.baseUrl)
    val customUrlMissing = state.selectedProvider == com.trae.social.llm.LlmProvider.CUSTOM &&
        state.baseUrl.isBlank()
    val urlMissingError = urlTouched && customUrlMissing

    val canSubmit = state.apiKey.isNotBlank() &&
        isHttpUrl(state.baseUrl) &&
        state.model.isNotBlank()

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
            text = "配置 ${state.selectedProvider.displayName()}",
            style = typography.title1,
            color = colors.label,
        )
        Text(
            text = "填写 API Key 与端点信息，所有数据均加密存储于本地",
            style = typography.subheadline,
            color = colors.secondaryLabel,
        )

        Spacer(Modifier.padding(8.dp))

        // API Key 输入
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = {
                viewModel.updateApiKey(it)
                keyTouched = true
            },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = if (showKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (showKey) "隐藏 Key" else "显示 Key",
                    )
                }
            },
            isError = keyError,
            supportingText = {
                if (keyError) {
                    Text(
                        text = "API Key 不能为空",
                        color = colors.systemRed,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Base URL 输入
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = {
                viewModel.updateBaseUrl(it)
                urlTouched = true
            },
            label = { Text("Base URL") },
            singleLine = true,
            placeholder = {
                Text(
                    text = "https://api.example.com",
                    color = colors.tertiaryLabel,
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = urlError || urlMissingError,
            supportingText = {
                when {
                    urlMissingError -> Text(
                        text = "自定义端点必须填写 Base URL",
                        color = colors.systemRed,
                    )
                    urlError -> Text(
                        text = "URL 格式不合法（需以 http:// 或 https:// 开头）",
                        color = colors.systemRed,
                    )
                    else -> Text(
                        text = "留空则使用提供商官方端点（自定义端点必填）",
                        color = colors.tertiaryLabel,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // 模型名输入
        OutlinedTextField(
            value = state.model,
            onValueChange = { viewModel.updateModel(it) },
            label = { Text("模型名") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )

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
                Text(text = "返回", style = typography.body, color = colors.label)
            }
            ActionButton(
                text = "测试连接",
                onClick = onTest,
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 校验字符串是否为合法 http/https URL。
 *
 * 自定义端点的 Base URL 必须通过该校验。
 */
private fun isHttpUrl(url: String): Boolean {
    if (url.isBlank()) return false
    return try {
        val parsed = URL(url)
        parsed.protocol == "http" || parsed.protocol == "https"
    } catch (e: Exception) {
        false
    }
}

/**
 * 将 [com.trae.social.llm.LlmProvider] 映射为用户可读的展示名。
 *
 * core-llm 的 LlmProvider 枚举无 displayName 字段，此处集中维护映射，
 * 与 core-data 的 LlmProvider.displayName 保持一致。
 */
internal fun com.trae.social.llm.LlmProvider.displayName(): String = when (this) {
    com.trae.social.llm.LlmProvider.OPENAI -> "OpenAI"
    com.trae.social.llm.LlmProvider.ANTHROPIC -> "Anthropic"
    com.trae.social.llm.LlmProvider.GEMINI -> "Google Gemini"
    com.trae.social.llm.LlmProvider.CUSTOM -> "自定义端点"
}
