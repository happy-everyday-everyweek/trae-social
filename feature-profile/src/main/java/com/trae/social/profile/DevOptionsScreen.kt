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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.core.data.entity.SchedulerLogEntity
import com.trae.social.designsystem.components.SocialCard
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.theme.socialColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开发者选项页（IMPL-2 + RISK-15：可观测性）。
 *
 * 展示当前 AI 活跃度档位与最近调度日志。
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
    val colors = socialColors()

    Column(modifier.fillMaxSize().background(colors.systemBackground)) {
        TopAppBar(
            title = { Text("开发者选项", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Text(
            "当前活跃度档位：${activityLevel.name}（${activityLevel.rpmLimit} RPM，${activityLevel.dailyPostsPerAccount} 条/天）",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondaryLabel,
            modifier = Modifier.padding(16.dp),
        )
        SocialDivider()
        Text(
            "调度日志（最近 ${logs.size} 条）",
            style = MaterialTheme.typography.titleSmall,
            color = colors.secondaryLabel,
            modifier = Modifier.padding(16.dp),
        )
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("暂无调度日志", color = colors.tertiaryLabel)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(logs, key = { it.id }) { log ->
                    LogRow(log)
                    SocialDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun LogRow(log: SchedulerLogEntity) {
    val colors = socialColors()
    val timeFmt = androidx.compose.runtime.remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${log.action} · ${log.result}",
                fontWeight = FontWeight.Medium,
                color = colors.label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${log.durationMs}ms",
                color = colors.tertiaryLabel,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "${timeFmt.format(Date(log.timestamp))} · ${log.accountId}",
            color = colors.tertiaryLabel,
            style = MaterialTheme.typography.labelSmall,
        )
        log.errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
            Spacer(Modifier.height(4.dp))
            Text(
                err,
                color = colors.systemRed,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
