package com.trae.social.profile

import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.designsystem.components.SocialCard
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.theme.socialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 设置页（IMPL-2）。
 *
 * AI 活跃度档位切换、API Key 管理、人设管理、清除缓存、关于、开发者选项（连点 7 次解锁）。
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // IMPL-2 / spec L463：用 rememberSaveable 持久化 7 次连点解锁状态
    var devOptionsUnlocked by rememberSaveable { mutableStateOf(false) }
    var aboutTapCount by rememberSaveable { mutableIntStateOf(0) }

    // 弹窗状态
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(colors.systemBackground)) {
        Column(Modifier.fillMaxSize()) {
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
                    SettingsEntryRow(title = "人设管理", onClick = {
                        // 人设管理跳转至个人页面（当前用户人设展示在 Profile 页）
                        onBack()
                    })
                    SocialDivider(thickness = 0.5.dp)
                    SettingsEntryRow(title = "清除缓存", onClick = { showClearCacheDialog = true })
                    SocialDivider(thickness = 0.5.dp)
                    // IMPL-2 / spec L439："关于"连点 7 次解锁"开发者选项"
                    SettingsEntryRow(title = "关于", onClick = {
                        showAboutDialog = true
                        if (!devOptionsUnlocked) {
                            aboutTapCount++
                            if (aboutTapCount >= REQUIRED_TAPS_TO_UNLOCK) {
                                devOptionsUnlocked = true
                                scope.launch {
                                    snackbarHostState.showSnackbar("开发者选项已解锁")
                                }
                            }
                        }
                    })
                    // 开发者选项：解锁后显示
                    if (devOptionsUnlocked) {
                        SocialDivider(thickness = 0.5.dp)
                        SettingsEntryRow(title = "开发者选项", onClick = onNavigateToDevOptions)
                    }
                }
            }
        }

        // 关于弹窗
        if (showAboutDialog) {
            val versionName = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrElse { "unknown" }
            }
            val remainingTaps = REQUIRED_TAPS_TO_UNLOCK - aboutTapCount
            AlertDialog(
                onDismissRequest = { showAboutDialog = false },
                title = { Text("关于") },
                text = {
                    Column {
                        Text("Trae Social")
                        Spacer(Modifier.height(4.dp))
                        Text("版本：$versionName", style = MaterialTheme.typography.bodySmall, color = colors.tertiaryLabel)
                        if (!devOptionsUnlocked && remainingTaps > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "再点击关于 $remainingTaps 次可解锁开发者选项",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.tertiaryLabel,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAboutDialog = false }) { Text("确定") }
                },
            )
        }

        // 清除缓存确认弹窗
        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                title = { Text("清除缓存") },
                text = { Text("确定清除应用缓存？此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = {
                        showClearCacheDialog = false
                        scope.launch {
                            val cleared = clearCache(context)
                            snackbarHostState.showSnackbar("已清除缓存 ${cleared}KB")
                        }
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheDialog = false }) { Text("取消") }
                },
            )
        }

        // Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * 清除应用缓存目录，返回清除的字节数。
 */
private suspend fun clearCache(context: Context): Long = withContext(Dispatchers.IO) {
    var total = 0L
    runCatching {
        fun deleteDir(dir: java.io.File): Long {
            var size = 0L
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { child ->
                    size += deleteDir(child)
                }
            }
            val len = dir.length()
            if (dir.delete()) size += len
            return size
        }
        total += deleteDir(context.cacheDir)
        // 清除 Coil 图片缓存目录
        runCatching {
            val cacheDir = java.io.File(context.cacheDir, "image_cache")
            if (cacheDir.exists()) total += deleteDir(cacheDir)
        }
    }.onFailure { Timber.w(it, "清除缓存失败") }
    total / 1024
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

/** spec L439：连点 7 次解锁开发者选项 */
private const val REQUIRED_TAPS_TO_UNLOCK = 7
