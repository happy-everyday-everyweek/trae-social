package com.trae.social.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.designsystem.components.SocialCard
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.theme.socialColors

/**
 * 设置页（IMPL-2）。
 *
 * AI 活跃度档位切换、API Key 管理入口、开发者选项入口。
 *
 * @param onBack 返回
 * @param onNavigateToApiKey 进入 API Key 管理
 * @param onNavigateToDevOptions 进入开发者选项
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToApiKey: () -> Unit,
    onNavigateToDevOptions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val activityLevel by viewModel.activityLevel.collectAsStateWithLifecycle()
    val colors = socialColors()

    Column(modifier.fillMaxSize().background(colors.systemBackground)) {
        TopAppBar(
            title = { Text("设置", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Spacer(Modifier.height(8.dp))

        // AI 活跃度档位
        Text(
            "AI 活跃度",
            style = MaterialTheme.typography.titleSmall,
            color = colors.secondaryLabel,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SocialCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                AiActivityLevel.values().forEach { level ->
                    ActivityLevelRow(
                        level = level,
                        selected = level == activityLevel,
                        onClick = { viewModel.setActivityLevel(level) },
                    )
                    if (level.ordinal < AiActivityLevel.values().lastIndex) {
                        SocialDivider(thickness = 0.5.dp)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // 功能入口
        Text(
            "高级",
            style = MaterialTheme.typography.titleSmall,
            color = colors.secondaryLabel,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SocialCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingsEntryRow(title = "API Key 管理", onClick = onNavigateToApiKey)
                SocialDivider(thickness = 0.5.dp)
                SettingsEntryRow(title = "开发者选项", onClick = onNavigateToDevOptions)
            }
        }
    }
}

@Composable
private fun ActivityLevelRow(
    level: AiActivityLevel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = socialColors()
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(level.displayName(), fontWeight = FontWeight.Medium, color = colors.label)
            Text(
                "约 ${level.dailyPostsPerAccount} 条/天，${level.rpmLimit} RPM",
                style = MaterialTheme.typography.bodySmall,
                color = colors.tertiaryLabel,
            )
        }
        if (selected) {
            Box(
                Modifier.clip(RoundedCornerShape(4.dp))
                    .background(colors.systemBlue)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("当前", color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SettingsEntryRow(title: String, onClick: () -> Unit) {
    val colors = socialColors()
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = colors.label, fontWeight = FontWeight.Medium)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.tertiaryLabel,
        )
    }
}

private fun AiActivityLevel.displayName(): String = when (this) {
    AiActivityLevel.LOW -> "低 (LOW)"
    AiActivityLevel.MEDIUM -> "中 (MEDIUM)"
    AiActivityLevel.HIGH -> "高 (HIGH)"
}
