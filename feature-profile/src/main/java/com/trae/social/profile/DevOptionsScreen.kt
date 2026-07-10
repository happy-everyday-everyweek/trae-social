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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.core.data.entity.SchedulerLogEntity
import com.trae.social.designsystem.components.SocialCard
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.theme.socialColors
import com.trae.social.designsystem.theme.LocalSocialTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 开发者选项页（IMPL-2 + RISK-15：可观测性）。
 *
 * 展示当前 AI 活跃度档位、LLM 调用统计、手动触发调度、最近调度日志。
 *
 * @param onBack 返回
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevOptionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DevOptionsViewModel = hiltViewModel(),
) {
    val logs by viewModel.logsFlow.collectAsStateWithLifecycle()
    val activityLevel by viewModel.activityLevel.collectAsStateWithLifecycle()
    val llmStats by viewModel.llmStats.collectAsStateWithLifecycle()
    val actionCounts by viewModel.actionCounts.collectAsStateWithLifecycle()
    val triggerResult by viewModel.triggerResult.collectAsStateWithLifecycle()
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // RISK-15：手动触发结果通过 Snackbar 反馈
    LaunchedEffect(triggerResult) {
        triggerResult?.let {
            scope.launch {
                snackbarHostState.showSnackbar(it, withDismissAction = true)
                viewModel.clearTriggerResult()
            }
        }
    }

    Box(modifier.fillMaxSize().background(colors.systemBackground)) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("开发者选项", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )

            LazyColumn(Modifier.fillMaxSize()) {
                // 当前活跃度档位
                item {
                    Text(
                        "当前活跃度档位：${activityLevel.name}（${activityLevel.rpmLimit} RPM，${activityLevel.dailyPostsPerAccount} 条/天）",
                        style = typography.callout,
                        color = colors.secondaryLabel,
                        modifier = Modifier.padding(16.dp),
                    )
                    SocialDivider()
                }

                // RISK-15：手动触发调度
                item {
                    Text(
                        "手动触发调度",
                        style = typography.subheadline,
                        color = colors.secondaryLabel,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    SocialCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.triggerTweetGeneration() },
                                    modifier = Modifier.weight(1f),
                                ) { Text("推文生成") }
                                OutlinedButton(
                                    onClick = { viewModel.triggerPendingInteractions() },
                                    modifier = Modifier.weight(1f),
                                ) { Text("互动处理") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.triggerPersonaUpdate() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("人设更新") }
                        }
                    }
                    SocialDivider()
                }

                // RISK-15：LLM 调用统计
                item {
                    Text(
                        "LLM 调用统计",
                        style = typography.subheadline,
                        color = colors.secondaryLabel,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    SocialCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            val stats = llmStats
                            if (stats == null) {
                                Text("加载中...", color = colors.tertiaryLabel)
                            } else {
                                StatRow("总调用", stats.totalCount)
                                StatRow("成功", stats.successCount)
                                StatRow("失败", stats.errorCount)
                                StatRow("限流(429)", stats.rateLimitedCount)
                                if (stats.totalCount > 0) {
                                    val rate = stats.successCount * 100 / stats.totalCount
                                    StatRow("成功率", suffix = "$rate%")
                                }
                            }
                            if (actionCounts.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                SocialDivider(thickness = 0.5.dp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "按类型分布",
                                    style = typography.caption1,
                                    color = colors.tertiaryLabel,
                                )
                                actionCounts.forEach { ac ->
                                    Text(
                                        "  ${ac.action}: ${ac.count}",
                                        style = typography.subheadline,
                                        color = colors.secondaryLabel,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.refreshStats() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("刷新统计") }
                        }
                    }
                    SocialDivider()
                }

                // 调度日志
                item {
                    Text(
                        "调度日志（最近 ${logs.size} 条）",
                        style = typography.subheadline,
                        color = colors.secondaryLabel,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                if (logs.isEmpty()) {
                    item {
                        // #31：调度日志空状态——图标 + 友好文案，避免冷冰冰的单行文字
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "调度尚未运行，稍后再来看"
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(colors.systemBlue.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.History,
                                    contentDescription = null,
                                    tint = colors.systemBlue,
                                )
                            }
                            Text(
                                text = "调度尚未运行",
                                style = typography.subheadline,
                                color = colors.secondaryLabel,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "稍后再来看",
                                style = typography.subheadline,
                                color = colors.tertiaryLabel,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    items(logs, key = { it.id }) { log ->
                        LogRow(log)
                        SocialDivider(thickness = 0.5.dp)
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun StatRow(label: String, count: Int? = null, suffix: String? = null) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = colors.secondaryLabel, style = typography.callout)
        Text(
            count?.toString() ?: suffix ?: "",
            color = colors.label,
            style = typography.callout,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LogRow(log: SchedulerLogEntity) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${log.action} · ${log.result}",
                fontWeight = FontWeight.Medium,
                color = colors.label,
                style = typography.callout,
            )
            Text(
                "${log.durationMs}ms",
                color = colors.tertiaryLabel,
                style = typography.caption2,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "${timeFmt.format(Date(log.timestamp))} · ${log.accountId}",
            color = colors.tertiaryLabel,
            style = typography.caption2,
        )
        log.errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
            Spacer(Modifier.height(4.dp))
            Text(
                err,
                color = colors.systemRed,
                style = typography.subheadline,
            )
        }
    }
}
