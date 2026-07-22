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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.trae.social.core.data.config.LlmProtocol
import com.trae.social.designsystem.components.SocialCard
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.theme.socialColors
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * API Key / 端点管理页（#151 重构：多端点 CRUD + 排序）。
 *
 * 列表展示用户配置的所有端点（按 orderIndex 升序），首位为主端点（默认生成模型）。
 * 每个端点卡片可编辑 displayName / protocol / Base URL / 模型名 / API Key；
 * 支持"设为主端点"（moveToFront）与删除操作。
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

    // 各端点的临时编辑态
    val apiKeyDrafts = remember { mutableStateMapOf<String, String>() }
    val baseUrlDrafts = remember { mutableStateMapOf<String, String>() }
    val modelDrafts = remember { mutableStateMapOf<String, String>() }
    val displayNameDrafts = remember { mutableStateMapOf<String, String>() }

    Column(modifier.fillMaxSize().background(colors.systemBackground)) {
        TopAppBar(
            title = { Text("端点管理", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = {
                    // 添加端点：用占位值创建，用户后续在卡片中编辑
                    viewModel.addEndpoint(
                        displayName = "新端点",
                        protocol = LlmProtocol.OPENAI_COMPATIBLE,
                        baseUrl = LlmProtocol.OPENAI_COMPATIBLE.defaultBaseUrl,
                        model = "gpt-4o-mini",
                        apiKey = "",
                    )
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "添加端点")
                }
            },
        )
        if (state.loading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("加载中…", color = colors.tertiaryLabel)
            }
            return@Column
        }
        if (state.endpoints.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("尚未配置任何端点，点击右上角 + 添加", color = colors.tertiaryLabel)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(state.endpoints, key = { it.id }) { cfg ->
                EndpointConfigCard(
                    config = cfg,
                    isPrimary = cfg.orderIndex == 0,
                    apiKeyDraft = apiKeyDrafts[cfg.id] ?: "",
                    onApiKeyChange = { apiKeyDrafts[cfg.id] = it },
                    baseUrlDraft = baseUrlDrafts[cfg.id] ?: cfg.baseUrl,
                    onBaseUrlChange = { baseUrlDrafts[cfg.id] = it },
                    modelDraft = modelDrafts[cfg.id] ?: cfg.model,
                    onModelChange = { modelDrafts[cfg.id] = it },
                    displayNameDraft = displayNameDrafts[cfg.id] ?: cfg.displayName,
                    onDisplayNameChange = { displayNameDrafts[cfg.id] = it },
                    onSaveKey = {
                        viewModel.setApiKey(cfg.id, it)
                        // #192：保存后立即清空 Key 草稿，避免输入框残留明文导致用户误判未保存
                        // 而重复提交，也消除敏感信息残留风险。setApiKey 虽为 fire-and-forget，
                        // 但保存成功后 loadAll 会刷新 cfg.apiKeyPreview（显示"当前: sk-xxx"），
                        // 用户可据预览变化判断已保存；若保存失败预览不变，用户重新输入即可。
                        apiKeyDrafts.remove(cfg.id)
                    },
                    onSaveEndpoint = {
                        viewModel.updateEndpoint(
                            id = cfg.id,
                            displayName = displayNameDrafts[cfg.id] ?: cfg.displayName,
                            protocol = cfg.protocol,
                            baseUrl = baseUrlDrafts[cfg.id] ?: cfg.baseUrl,
                            model = modelDrafts[cfg.id] ?: cfg.model,
                        )
                        // 主 review 第 2 轮修复：回退第 1 轮 M4 的草稿清除（同 onSaveKey 理由）。
                        // 保留草稿让用户可在保存失败时重试；保存成功后 loadAll 会刷新 cfg.*
                        // （baseUrl/model/displayName），但草稿会遮盖 cfg.*——这是可接受的：
                        // 用户看到的草稿即其输入值，与已保存值一致（保存成功时），或可重试（失败时）。
                    },
                    onSetPrimary = { viewModel.moveToFront(cfg.id) },
                    onDelete = { viewModel.deleteEndpoint(cfg.id) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun EndpointConfigCard(
    config: EndpointConfig,
    isPrimary: Boolean,
    apiKeyDraft: String,
    onApiKeyChange: (String) -> Unit,
    baseUrlDraft: String,
    onBaseUrlChange: (String) -> Unit,
    modelDraft: String,
    onModelChange: (String) -> Unit,
    displayNameDraft: String,
    onDisplayNameChange: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onSaveEndpoint: () -> Unit,
    onSetPrimary: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    SocialCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSetPrimary, enabled = !isPrimary) {
                        Icon(
                            if (isPrimary) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = if (isPrimary) "主端点" else "设为主端点",
                            tint = if (isPrimary) colors.systemBlue else colors.tertiaryLabel,
                        )
                    }
                    Text(displayNameDraft, fontWeight = FontWeight.Bold, color = colors.label)
                }
                if (isPrimary) {
                    Text("主端点", color = colors.systemBlue, style = typography.caption2)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${config.protocol.displayName} · #${config.orderIndex + 1}",
                style = typography.caption2,
                color = colors.tertiaryLabel,
            )
            Spacer(Modifier.height(8.dp))

            // 展示名
            Text("展示名", style = typography.caption1, color = colors.secondaryLabel)
            OutlinedTextField(
                value = displayNameDraft,
                onValueChange = onDisplayNameChange,
                label = { Text("展示名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SocialDivider(Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

            // Base URL
            Text("Base URL", style = typography.caption1, color = colors.secondaryLabel)
            OutlinedTextField(
                value = baseUrlDraft,
                onValueChange = onBaseUrlChange,
                label = { Text("Base URL") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )

            SocialDivider(Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

            // Model
            Text("模型名", style = typography.caption1, color = colors.secondaryLabel)
            OutlinedTextField(
                value = modelDraft,
                onValueChange = onModelChange,
                label = { Text("模型名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onSaveEndpoint,
                // #136：空值校验
                enabled = displayNameDraft.isNotBlank() &&
                    baseUrlDraft.isNotBlank() &&
                    modelDraft.isNotBlank(),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
            ) { Text("保存端点配置") }

            SocialDivider(Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

            // API Key
            Text("API Key", style = typography.caption1, color = colors.secondaryLabel)
            config.apiKeyPreview?.takeIf { it.isNotBlank() }?.let {
                Text("当前: $it", style = typography.subheadline, color = colors.tertiaryLabel)
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

            if (!isPrimary) {
                SocialDivider(Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)
                // IMPL-27 UI 侧根因：未配置 Key 的端点仍可设为主端点（用空 Key 调用会失败，
                // 但管理 UI 不强制约束——避免阻塞用户在调试期切换主端点）。
                Button(
                    onClick = onSetPrimary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("设为主端点")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除端点", tint = colors.systemRed)
                }
            }
        }
    }
}
