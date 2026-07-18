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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.core.data.model.RollbackPreview
import com.trae.social.designsystem.components.SocialCard
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.theme.LocalSocialTypography
import com.trae.social.designsystem.theme.socialColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 画像对话页（#146 第五层）。
 *
 * 类 LLM 对话界面：用户发送自然语言 → 智能体解析意图 → 即时应用结构化调整 → 回复说明。
 * 顶部展示当前画像摘要卡片；底部输入框 + 发送 + 重置 + 查看版本历史按钮。
 * 回滚意图需在对话中渲染预览卡片，用户点击"确认回滚"后才应用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileChatScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val activeVersion by viewModel.activeVersion.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val activeOverrides by viewModel.activeOverrides.collectAsStateWithLifecycle()
    val recentVersions by viewModel.recentVersions.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val pendingPreviews by viewModel.pendingPreviews.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    val colors = socialColors()
    val typography = LocalSocialTypography.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var showVersionHistory by remember { mutableStateOf(false) }

    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // 新消息到达或发送状态变化时滚动到底部
    // 第七轮 review M12 修复：
    // 1. 原索引 messages.size - 1 未考虑 LazyColumn 顶部 pendingPreviews 项的偏移，
    //    有预览时滚到的是消息列表中间而非最后一条。现预览已移出 LazyColumn，索引无需偏移。
    // 2. 增加 sending 触发：sending=true 时出现"思考中"指示器（最后一条 item），
    //    需滚动到 messages.size（指示器索引），否则用户看不到加载状态。
    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) {
            val targetIndex = if (sending) messages.size else messages.size - 1
            listState.animateScrollToItem(targetIndex)
        }
    }
    // 一次性 toast
    LaunchedEffect(toast) {
        toast?.let {
            scope.launch {
                snackbarHostState.showSnackbar(it, withDismissAction = true)
                viewModel.consumeToast()
            }
        }
    }

    Box(modifier.fillMaxSize().background(colors.systemBackground)) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("画像调校", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )

            // 顶部画像摘要卡片
            ProfileSummaryCard(
                activeVersion = activeVersion,
                snapshot = snapshot,
                activeOverrideCount = activeOverrides.size,
                onShowVersionHistory = {
                    viewModel.refreshRecentVersions()
                    showVersionHistory = !showVersionHistory
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            // 可折叠版本历史
            if (showVersionHistory) {
                SocialCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("版本历史（最近 ${recentVersions.size}）", style = typography.subheadline, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        if (recentVersions.isEmpty()) {
                            Text("暂无版本", color = colors.tertiaryLabel, style = typography.subheadline)
                        } else {
                            recentVersions.forEach { v ->
                                Text(
                                    "  #${v.id} · ${timeFmt.format(Date(v.createdAt))}" +
                                        (if (v.isActive) " · 当前激活" else "") +
                                        " · ${v.narrativePreview.take(40)}",
                                    color = if (v.isActive) colors.systemBlue else colors.secondaryLabel,
                                    style = typography.subheadline,
                                )
                            }
                        }
                    }
                }
            }

            SocialDivider()

            // 第七轮 review M12 修复：待确认的回滚预览移出 LazyColumn，置顶固定在消息流上方。
            // 原实现将预览放在 LazyColumn 内部作为首部 item，新消息到达时 animateScrollToItem
            // 滚到底部，预览随消息流滚出视口 → 用户看不到待确认的回滚卡片，无法确认/取消。
            // 移出后预览始终可见，LazyColumn 仅含消息流 + sending 指示器，滚动索引也无需偏移。
            if (pendingPreviews.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingPreviews.forEach { preview ->
                        RollbackPreviewCard(
                            preview = preview,
                            onConfirm = { viewModel.confirmRollback(preview) },
                            onDismiss = { viewModel.dismissPreview(preview) },
                        )
                    }
                }
                SocialDivider()
            }

            // 消息流
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "告诉画像你的偏好或调整要求，例如：\n" +
                                "- 我对科技和美食更感兴趣\n" +
                                "- 别再给我推荐宠物内容\n" +
                                "- 我白天更活跃，不是夜猫子\n" +
                                "- 恢复到上个画像版本\n\n" +
                                "发送的内容将用于调校你的画像。",
                            color = colors.tertiaryLabel,
                            style = typography.subheadline,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(msg = msg, timeFmt = timeFmt)
                    }
                    if (sending) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.Start,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(4.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    "  智能体思考中...",
                                    color = colors.tertiaryLabel,
                                    style = typography.subheadline,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            }
                        }
                    }
                }
            }

            SocialDivider()

            // 底部输入区
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("告诉画像你的偏好或调整要求...") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        sendCurrent(viewModel, input) { input = "" }
                    }),
                )
                IconButton(
                    onClick = { sendCurrent(viewModel, input) { input = "" } },
                    enabled = !sending && input.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.resetAllOverrides() },
                    modifier = Modifier.weight(1f),
                ) { Text("重置所有调整") }
                OutlinedButton(
                    onClick = {
                        viewModel.refreshRecentVersions()
                        showVersionHistory = !showVersionHistory
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (showVersionHistory) "隐藏版本历史" else "查看版本历史") }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun sendCurrent(viewModel: ProfileChatViewModel, text: String, clear: () -> Unit) {
    if (text.isBlank()) return
    viewModel.send(text)
    clear()
}

@Composable
private fun ProfileSummaryCard(
    activeVersion: com.trae.social.core.data.model.UserProfileVersion?,
    snapshot: com.trae.social.core.data.model.UserProfileSnapshot?,
    activeOverrideCount: Int,
    onShowVersionHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    SocialCard(modifier = modifier.padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            if (activeVersion == null && snapshot == null) {
                Text(
                    "暂无画像数据。多使用应用、与内容互动，画像会逐渐建立。",
                    color = colors.tertiaryLabel,
                    style = typography.subheadline,
                )
            } else {
                activeVersion?.let { v ->
                    Text(
                        "当前版本 #${v.id} · ${timeFmt.format(Date(v.createdAt))}" +
                            (if (!v.isActive) " · 已回滚激活" else ""),
                        style = typography.subheadline,
                        color = colors.systemBlue,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        v.narrative.take(120) + if (v.narrative.length > 120) "..." else "",
                        style = typography.subheadline,
                        color = colors.label,
                    )
                    if (v.overrideAcknowledgment.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已确认调整：${v.overrideAcknowledgment.joinToString("；")}",
                            style = typography.caption2,
                            color = colors.tertiaryLabel,
                        )
                    }
                }
                snapshot?.let { s ->
                    if (activeVersion == null) {
                        Text(
                            "基础分析快照 · 置信度 ${(s.confidence.overall * 100).toInt()}%",
                            style = typography.subheadline,
                            color = colors.systemBlue,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Top 兴趣：${s.evidence.topThemes.take(3).joinToString { "${it.theme}(${(it.weight * 100).toInt()}%)" }}",
                        style = typography.caption2,
                        color = colors.tertiaryLabel,
                    )
                    Text(
                        "活跃时段：${s.evidence.topActiveHours.take(3).joinToString { "${it.hour}:00" }} · 异常剔除 ${s.evidence.anomalyCount} 条",
                        style = typography.caption2,
                        color = colors.tertiaryLabel,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "生效用户覆盖：$activeOverrideCount 条",
                    style = typography.caption2,
                    color = colors.tertiaryLabel,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onShowVersionHistory,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("查看 / 回滚版本历史") }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    timeFmt: SimpleDateFormat,
) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    val isUser = msg.role == ChatMessage.Role.USER
    val align = if (isUser) Alignment.End else Alignment.Start
    val bg = if (isUser) colors.systemBlue.copy(alpha = 0.12f) else colors.secondaryBackground
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isUser) 12.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 12.dp,
                    )
                )
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                Text(
                    msg.text,
                    color = colors.label,
                    style = typography.subheadline,
                )
                if (msg.appliedActions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已应用调整 ${msg.appliedActions.size} 条：" +
                            msg.appliedActions.joinToString("，") { "${it.type.id}=${it.key}" },
                        style = typography.caption2,
                        color = colors.systemBlue,
                    )
                }
                if (msg.degraded) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "（降级回复）",
                        style = typography.caption2,
                        color = colors.systemOrange,
                    )
                }
            }
        }
        Text(
            timeFmt.format(Date(msg.timestamp)),
            style = typography.caption2,
            color = colors.tertiaryLabel,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun RollbackPreviewCard(
    preview: RollbackPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    SocialCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "回滚预览 · 目标版本 #${preview.targetVersionId}",
                style = typography.subheadline,
                fontWeight = FontWeight.Medium,
                color = colors.systemOrange,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "目标时间：${timeFmt.format(Date(preview.targetCreatedAt))}",
                style = typography.caption2,
                color = colors.tertiaryLabel,
            )
            Spacer(Modifier.height(4.dp))
            Text("当前 narrative：", style = typography.caption2, color = colors.tertiaryLabel)
            Text(
                preview.currentNarrative.take(80) + if (preview.currentNarrative.length > 80) "..." else "",
                style = typography.subheadline,
                color = colors.label,
            )
            Spacer(Modifier.height(4.dp))
            Text("目标 narrative：", style = typography.caption2, color = colors.tertiaryLabel)
            Text(
                preview.targetNarrative.take(80) + if (preview.targetNarrative.length > 80) "..." else "",
                style = typography.subheadline,
                color = colors.label,
            )
            if (preview.feedbackWeightsDiff.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("权重变化：", style = typography.caption2, color = colors.tertiaryLabel)
                preview.feedbackWeightsDiff.forEach { (k, delta) ->
                    Text(
                        "  $k: ${"%.3f".format(delta.from)} → ${"%.3f".format(delta.to)} (Δ ${"%.3f".format(delta.delta)})",
                        style = typography.caption2,
                        color = colors.secondaryLabel,
                    )
                }
            }
            if (preview.affectedScenarios.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "受影响场景：${preview.affectedScenarios.joinToString()}",
                    style = typography.caption2,
                    color = colors.systemOrange,
                )
            }
            if (preview.overridesToPreserve.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "保留覆盖：${preview.overridesToPreserve.size} 条（覆盖优先级 > 版本）",
                    style = typography.caption2,
                    color = colors.tertiaryLabel,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text("取消") }
                OutlinedButton(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                ) { Text("确认回滚") }
            }
        }
    }
}
