package com.trae.social.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.core.data.config.LlmProvider
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
@OptIn(ExperimentalMaterial3Api::class)
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
    val context = LocalContext.current

    var showKey by remember { mutableStateOf(false) }
    var keyTouched by remember { mutableStateOf(false) }
    var urlTouched by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val keyError = keyTouched && state.apiKey.isBlank()
    val urlError = urlTouched && state.baseUrl.isNotBlank() && !isHttpUrl(state.baseUrl)
    val customUrlMissing = state.selectedProvider == LlmProvider.CUSTOM &&
        state.baseUrl.isBlank()
    val urlMissingError = urlTouched && customUrlMissing

    // #34：检测粘贴/输入的 API Key 前缀，提示用户切换到对应提供商
    val detectedProvider = remember(state.apiKey) {
        detectProviderFromKey(state.apiKey)
    }
    val showProviderHint = detectedProvider != null &&
        detectedProvider != state.selectedProvider &&
        state.apiKey.length >= 4

    // #189：非 CUSTOM 提供商允许留空 baseUrl（回退官方端点，与 supportingText 文案一致）；
    // CUSTOM 必须填写合法 http(s) URL。原逻辑对空串一律判定不可提交，与
    // 「留空则使用提供商官方端点」提示矛盾。
    val canSubmit = state.apiKey.isNotBlank() &&
        state.model.isNotBlank() &&
        when (state.selectedProvider) {
            LlmProvider.CUSTOM -> isHttpUrl(state.baseUrl)
            else -> state.baseUrl.isBlank() || isHttpUrl(state.baseUrl)
        }

    // #34：当前提供商推荐的模型列表，供下拉选择
    val recommendedModels = remember(state.selectedProvider) {
        RECOMMENDED_MODELS[state.selectedProvider].orEmpty()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.systemBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            // IMPL-49 / #41：顶部/底部系统栏 inset 由 OnboardingNavHost 统一处理
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "配置 ${state.selectedProvider.displayName}",
            style = typography.title1,
            color = colors.label,
        )
        Text(
            text = "填写 API Key 与端点信息，所有数据均通过 Android Keystore 加密存储于本地",
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
                when {
                    keyError -> Text(
                        text = "API Key 不能为空",
                        color = colors.systemRed,
                    )
                    // #34：粘贴识别——检测到已知提供商前缀时提示切换
                    showProviderHint -> Text(
                        text = "检测到 ${detectedProvider!!.displayName} Key，是否切换提供商？",
                        color = colors.systemBlue,
                    )
                    else -> Text(
                        text = "支持 sk-（OpenAI）、sk-ant-（Anthropic）等前缀自动识别",
                        color = colors.tertiaryLabel,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // #34：粘贴识别——检测到不同提供商时提供一键切换按钮
        if (showProviderHint) {
            TextButton(
                onClick = { viewModel.selectProvider(detectedProvider!!) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "切换到 ${detectedProvider!!.displayName}",
                    color = colors.systemBlue,
                    style = typography.body,
                )
            }
        }

        // #34：历史 Key 快速选择——加密存储于本地，点击即回填到输入框
        if (state.historyApiKeys.isNotEmpty()) {
            Text(
                text = "历史 Key（点击快速填入）",
                style = typography.subheadline,
                color = colors.secondaryLabel,
            )
            state.historyApiKeys.forEach { historyKey ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.secondaryBackground)
                        .clickable { viewModel.selectHistoryApiKey(historyKey) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = colors.tertiaryLabel,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = maskApiKey(historyKey),
                        style = typography.body,
                        color = colors.label,
                    )
                }
            }
        }

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

        // #34：模型名输入——提供商推荐模型下拉 + 自定义输入
        ExposedDropdownMenuBox(
            expanded = modelMenuExpanded && recommendedModels.isNotEmpty(),
            onExpandedChange = { modelMenuExpanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = state.model,
                onValueChange = { viewModel.updateModel(it) },
                label = { Text("模型名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                trailingIcon = {
                    if (recommendedModels.isNotEmpty()) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // Review fix：使用非 deprecated 的 menuAnchor 重载
                    .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true),
            )
            if (recommendedModels.isNotEmpty()) {
                // ExposedDropdownMenu 在 Material3 1.3 已移除，改用 DropdownMenu；
                // menuAnchor 修饰符标记文本框为锚点，DropdownMenu 在其下方定位
                DropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false },
                ) {
                    recommendedModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                viewModel.updateModel(model)
                                modelMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // #34：提供商官方获取 API Key 的链接
        val keyUrl = remember(state.selectedProvider) {
            PROVIDER_KEY_URLS[state.selectedProvider]
        }
        if (keyUrl != null) {
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "前往 ${state.selectedProvider.displayName} 获取 API Key",
                    color = colors.systemBlue,
                    style = typography.subheadline,
                )
            }
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
        // review 第 5 轮修复：仅校验协议不足以拦截 "http://" 这类空 host 输入，
        // 会通过 canSubmit 后 ping 到无 host 的 URL 报 UnknownHostException。
        // 追加 host 非空校验。
        (parsed.protocol == "http" || parsed.protocol == "https") && parsed.host.isNotBlank()
    } catch (e: Exception) {
        false
    }
}

/**
 * #34：对历史 API Key 做脱敏预览，避免在历史列表中暴露完整密钥。
 *
 * 规则与 ConfigRepository.endpointApiKeyPreview 一致：
 * - 长度 <= 8 时仅显示 `***`，避免短 Key 被反推
 * - 否则取首 4 + `***` + 尾 4，保留前缀便于识别提供商（sk- / sk-ant- 等）
 */
private fun maskApiKey(key: String): String {
    if (key.length <= 8) return "***"
    return key.take(4) + "***" + key.takeLast(4)
}

// IMPL-44：displayName 直接使用 core-data LlmProvider.displayName 属性，
// 不再需要本地扩展函数维护重复映射。

/**
 * #34：根据 API Key 前缀识别所属提供商。
 *
 * - `sk-ant-` 开头：Anthropic
 * - `sk-` 开头（非 ant）：OpenAI
 * - `AIza` 开头：Google Gemini
 *
 * @return 识别到的提供商，未匹配返回 null
 */
private fun detectProviderFromKey(key: String): LlmProvider? {
    if (key.length < 4) return null
    return when {
        key.startsWith("sk-ant-", ignoreCase = true) -> LlmProvider.ANTHROPIC
        key.startsWith("sk-", ignoreCase = true) -> LlmProvider.OPENAI
        // Review fix：Google API Key 前缀大小写敏感，不忽略大小写避免 aiza/AIZA 误判
        key.startsWith("AIza") -> LlmProvider.GEMINI
        else -> null
    }
}

/**
 * #34：各提供商推荐模型列表，供模型名输入下拉选择。
 */
private val RECOMMENDED_MODELS: Map<LlmProvider, List<String>> = mapOf(
    LlmProvider.OPENAI to listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo"),
    LlmProvider.ANTHROPIC to listOf(
        "claude-3-5-sonnet-20240620",
        "claude-3-5-haiku-20241022",
        "claude-3-opus-20240229",
    ),
    LlmProvider.GEMINI to listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash"),
)

/**
 * #34：各提供商官方获取 API Key 的链接。
 */
private val PROVIDER_KEY_URLS: Map<LlmProvider, String> = mapOf(
    LlmProvider.OPENAI to "https://platform.openai.com/api-keys",
    LlmProvider.ANTHROPIC to "https://console.anthropic.com/settings/keys",
    LlmProvider.GEMINI to "https://aistudio.google.com/app/apikey",
)
