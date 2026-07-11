package com.trae.social.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.designsystem.components.SocialCard
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.theme.LocalSocialTypography
import com.trae.social.designsystem.theme.ThemeMode
import com.trae.social.designsystem.theme.ThemePreferences
import com.trae.social.designsystem.theme.socialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 设置页（IMPL-2）。
 *
 * 外观（浅色/深色/跟随系统）、AI 活跃度档位切换、API Key 管理、人设管理、清除缓存、关于、
 * 开发者选项（连点 7 次解锁）。
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
    val typography = LocalSocialTypography.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // IMPL-2 / spec L463：用 rememberSaveable 持久化 7 次连点解锁状态
    var devOptionsUnlocked by rememberSaveable { mutableStateOf(false) }
    var aboutTapCount by rememberSaveable { mutableIntStateOf(0) }

    // 弹窗状态
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }

    // #12：主题模式（读取全局可观察状态，切换后整页与 SocialTheme 重组）
    val currentThemeMode = ThemePreferences.themeMode
    // 关于卡片所需版本号
    val appVersionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrElse { "unknown" }
    }

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
            // #12：内容区可滚动，避免条目增多后溢出
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))

                // 外观：浅色 / 深色 / 跟随系统
                Text(
                    "外观",
                    style = typography.subheadline,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SocialCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column {
                        ThemeModeRow(
                            title = "浅色",
                            selected = currentThemeMode == ThemeMode.LIGHT,
                            onClick = { ThemePreferences.setThemeMode(context, ThemeMode.LIGHT) },
                        )
                        SocialDivider(thickness = 0.5.dp)
                        ThemeModeRow(
                            title = "深色",
                            selected = currentThemeMode == ThemeMode.DARK,
                            onClick = { ThemePreferences.setThemeMode(context, ThemeMode.DARK) },
                        )
                        SocialDivider(thickness = 0.5.dp)
                        ThemeModeRow(
                            title = "跟随系统",
                            selected = currentThemeMode == ThemeMode.SYSTEM,
                            onClick = { ThemePreferences.setThemeMode(context, ThemeMode.SYSTEM) },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // AI 活跃度档位
                Text(
                    "AI 活跃度",
                    style = typography.subheadline,
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
                    style = typography.subheadline,
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

                Spacer(Modifier.height(24.dp))

                // #12：关于（版本号 / 开源协议 / 免责声明 / GitHub）
                Text(
                    "关于本应用",
                    style = typography.subheadline,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SocialCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column {
                        AboutInfoRow(label = "版本", value = appVersionName)
                        SocialDivider(thickness = 0.5.dp)
                        AboutInfoRow(label = "开源协议", value = "PolyForm Noncommercial")
                        SocialDivider(thickness = 0.5.dp)
                        // #12：GitHub 行可点击，跳转至仓库页面
                        AboutInfoRow(
                            label = "GitHub",
                            value = "github.com/happy-everyday-everyweek/trae-social",
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/happy-everyday-everyweek/trae-social"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                        )
                        SocialDivider(thickness = 0.5.dp)
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "免责声明",
                                style = typography.subheadline,
                                color = colors.secondaryLabel,
                            )
                            Spacer(Modifier.height(4.dp))
                            // #12：补充加密存储说明，与 KeyInputScreen 副标题一致
                            Text(
                                "本应用为开源学习项目，不收集任何用户隐私数据。所有 API Key 均通过 Android Keystore 加密存储于本地，内容均在设备端生成与存储。",
                                style = typography.subheadline,
                                color = colors.tertiaryLabel,
                            )
                        }
                    }
                }

                // 底部导航栏留白已由 Column 的 navigationBarsPadding 处理
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
                        Text("版本：$versionName", style = typography.subheadline, color = colors.tertiaryLabel)
                        if (!devOptionsUnlocked && remainingTaps > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "再点击关于 $remainingTaps 次可解锁开发者选项",
                                style = typography.caption2,
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
    val typography = LocalSocialTypography.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(level.displayName(), fontWeight = FontWeight.Medium, color = colors.label)
            Text(
                "约 ${level.dailyPostsPerAccount} 条/天，${level.rpmLimit} RPM",
                style = typography.subheadline,
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

/**
 * #12：主题模式单选行，左侧标题，右侧 RadioButton。
 */
@Composable
private fun ThemeModeRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = socialColors()
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = colors.label, fontWeight = FontWeight.Medium)
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = colors.systemBlue),
        )
    }
}

/**
 * #12：关于卡片中的键值信息行。
 *
 * @param onClick 可选点击回调；非空时整行可点击（如 GitHub 链接跳转）
 */
@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    val mod = if (onClick != null) {
        Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp)
    } else {
        Modifier.fillMaxWidth().padding(16.dp)
    }
    Row(
        modifier = mod,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.secondaryLabel, style = typography.subheadline)
        Text(
            value,
            color = if (onClick != null) colors.systemBlue else colors.label,
            style = typography.subheadline,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
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
