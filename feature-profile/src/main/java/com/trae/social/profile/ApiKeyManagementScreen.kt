package com.trae.social.profile

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.core.data.config.LlmProvider
import com.trae.social.designsystem.components.SocialCard
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.theme.socialColors

/**
 * API Key 管理页（IMPL-2）。
 *
 * 按 provider 展示并编辑 API Key / Base URL / 模型名，设置默认 provider。
 *
 * @param onBack 返回
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyManagementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ApiKeyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = socialColors()

    // 各 provider 的临时编辑态（key 字段）
    val apiKeyDrafts = remember { mutableStateMapOf<LlmProvider, String>() }
    val baseUrlDrafts = remember { mutableStateMapOf<LlmProvider, String>() }
    val modelDrafts = remember { mutableStateMapOf<LlmProvider, String>() }

    Column(modifier.fillMaxSize().background(colors.systemBackground)) {
        TopAppBar(
            title = { Text("API Key 管理", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        if (state.loading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("加载中…", color = colors.tertiaryLabel)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(state.providerConfigs, key = { it.provider.name }) { cfg ->
                ProviderConfigCard(
                    config = cfg,
                    isDefault = state.defaultProvider == cfg.provider,
                    apiKeyDraft = apiKeyDrafts[cfg.provider] ?: "",
                    onApiKeyChange = { apiKeyDrafts[cfg.provider] = it },
                    baseUrlDraft = baseUrlDrafts[cfg.provider] ?: cfg.baseUrl,
                    onBaseUrlChange = { baseUrlDrafts[cfg.provider] = it },
                    modelDraft = modelDrafts[cfg.provider] ?: cfg.modelName,
                    onModelChange = { modelDrafts[cfg.provider] = it },
                    onSaveKey = { viewModel.setApiKey(cfg.provider, it) },
                    onSaveBaseUrl = { viewModel.setBaseUrl(cfg.provider, it) },
                    onSaveModel = { viewModel.setModelName(cfg.provider, it) },
                    onSetDefault = { viewModel.setDefaultProvider(cfg.provider) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ProviderConfigCard(
    config: ProviderConfig,
    isDefault: Boolean,
    apiKeyDraft: String,
    onApiKeyChange: (String) -> Unit,
    baseUrlDraft: String,
    onBaseUrlChange: (String) -> Unit,
    modelDraft: String,
    onModelChange: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onSaveBaseUrl: (String) -> Unit,
    onSaveModel: (String) -> Unit,
    onSetDefault: () -> Unit,
) {
    val colors = socialColors()
    SocialCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(config.provider.displayName, fontWeight = FontWeight.Bold, color = colors.label)
                if (isDefault) {
                    Text("默认", color = colors.systemBlue, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))

            // API Key
            Text("API Key", style = MaterialTheme.typography.labelMedium, color = colors.secondaryLabel)
            config.apiKeyPreview?.takeIf { it.isNotBlank() }?.let {
                Text("当前: $it", style = MaterialTheme.typography.bodySmall, color = colors.tertiaryLabel)
            }
            OutlinedTextField(
                value = apiKeyDraft,
                onValueChange = onApiKeyChange,
                label = { Text("输入新 API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSaveKey(apiKeyDraft) },
                enabled = apiKeyDraft.isNotBlank(),
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("保存 Key") }

            SocialDivider(Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

            // Base URL
            Text("Base URL", style = MaterialTheme.typography.labelMedium, color = colors.secondaryLabel)
            OutlinedTextField(
                value = baseUrlDraft,
                onValueChange = onBaseUrlChange,
                label = { Text("Base URL") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSaveBaseUrl(baseUrlDraft) },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("保存 URL") }

            SocialDivider(Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

            // Model
            Text("模型名", style = MaterialTheme.typography.labelMedium, color = colors.secondaryLabel)
            OutlinedTextField(
                value = modelDraft,
                onValueChange = onModelChange,
                label = { Text("模型名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSaveModel(modelDraft) },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("保存模型") }

            if (!isDefault) {
                SocialDivider(Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)
                Button(onClick = onSetDefault, modifier = Modifier.fillMaxWidth()) {
                    Text("设为默认 provider")
                }
            }
        }
    }
}
