package com.trae.social.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 引导欢迎页（SubTask 9.1）。
 *
 * 全屏布局：
 * - 顶部 Compose 几何插画（圆形 + 矩形组合，systemBlue 渐变）
 * - 标题"欢迎使用 Trae Social"
 * - 3 条要点副标题
 * - 免责声明（RISK-12）
 * - "开始配置"主按钮 + "稍后"文字按钮
 *
 * @param onStart 点击"开始配置"回调（导航至提供商选择页）
 * @param onSkip 点击"稍后"回调（标记跳过并进入主界面）
 */
@Composable
fun WelcomeScreen(
    onStart: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    // #35：进场动画——插画 alpha 与上移并行（500ms），内容淡入随后（400ms）
    val illustrationAlpha = remember { Animatable(0f) }
    val illustrationOffset = remember { Animatable(40f) }
    val contentAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Review fix #3：插画 alpha + offset 并行，完成后内容淡入
        coroutineScope {
            launch { illustrationAlpha.animateTo(1f, tween(500)) }
            launch { illustrationOffset.animateTo(0f, tween(500)) }
        }
        contentAlpha.animateTo(1f, tween(400))
    }

    // #15：免责声明可折叠，首次展示后用户可收起，减少主流程中的重复提示
    var disclaimerExpanded = remember { androidx.compose.runtime.mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.systemBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            // IMPL-49 / #41：顶部/底部系统栏 inset 由 OnboardingNavHost 统一处理
            .padding(top = 16.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        WelcomeIllustration(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .graphicsLayer {
                    alpha = illustrationAlpha.value
                    translationY = illustrationOffset.value
                },
        )

        Column(
            modifier = Modifier.graphicsLayer { alpha = contentAlpha.value },
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "欢迎使用 Trae Social",
                style = typography.largeTitle,
                color = colors.label,
                textAlign = TextAlign.Center,
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // #35：要点升级为图标 + 文案，比纯色圆点更具语义表达
                // #17：文案拟人化，避免过早暴露 LLM/虚拟账号/AI 生成等工程措辞
                BulletPoint(text = "高拟真社交生态，随时有人与你互动", icon = Icons.Filled.AutoAwesome)
                BulletPoint(text = "200+ 伙伴与你实时互动", icon = Icons.Filled.Group)
                BulletPoint(text = "完全本地运行，数据私密可控", icon = Icons.Filled.Lock)
            }

            Spacer(Modifier.height(8.dp))

            // #15：免责声明可折叠，点击标题展开/收起
            DisclaimerCard(
                text = "本应用为社交体验演示，内容由 AI 辅助生成，仅供学习与体验，不代表真实人物观点",
                expanded = disclaimerExpanded.value,
                onToggle = { disclaimerExpanded.value = !disclaimerExpanded.value },
            )
        }

        Spacer(Modifier.weight(1f))

        ActionButton(
            text = "开始配置",
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "稍后",
                style = typography.body,
                color = colors.secondaryLabel,
            )
        }
    }
}

/**
 * 顶部几何插画（#35 升级：多头像环绕 + 连线 + 卡片，模拟社交生态）。
 *
 * 完全由 Compose 绘制，无图片资源依赖：
 * - 中央大圆：systemBlue→systemPurple 渐变，代表社交核心
 * - 4 个小头像圆环绕四周，象征 200+ 伙伴
 * - 中央卡片矩形：模拟推文卡片
 */
@Composable
private fun WelcomeIllustration(modifier: Modifier = Modifier) {
    val colors = LocalSocialColors.current
    val blue = colors.systemBlue
    val purple = colors.systemPurple
    val green = colors.systemGreen
    val orange = colors.systemOrange

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // 大圆：渐变背景，代表社交生态核心
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(blue, purple),
                    ),
                ),
        )
        // #35：4 个环绕小头像圆，象征 200+ 伙伴
        // 左上
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 20.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(green.copy(alpha = 0.85f)),
        )
        // 右上
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp, top = 20.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(orange.copy(alpha = 0.8f)),
        )
        // 左下
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 28.dp, bottom = 16.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(purple.copy(alpha = 0.75f)),
        )
        // 右下
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 28.dp, bottom = 20.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(blue.copy(alpha = 0.7f)),
        )
        // 中央卡片：模拟推文卡片
        // #27：改用主题色 token 替代硬编码白色，避免深色模式纯黑背景上出现刺眼白块
        Box(
            modifier = Modifier
                .size(width = 100.dp, height = 64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.tertiaryBackground),
        )
    }
}

/**
 * 单条要点行：图标圆形徽标 + 文案（#35 升级，替代原 8dp 蓝点）。
 *
 * 图标置于 systemBlue 12% 透明的圆形底色中，tint 为 systemBlue，
 * 既保留品牌色基调，又比纯色圆点更具语义表达力。
 */
@Composable
private fun BulletPoint(text: String, icon: ImageVector) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.systemBlue.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.systemBlue,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            style = typography.body,
            color = colors.label,
        )
    }
}

/**
 * 免责声明卡片（RISK-12 / #15：可折叠）。
 *
 * 浅色背景 + info 图标 + 标题行（可点击展开/收起）+ 展开时显示详情文案。
 */
@Composable
private fun DisclaimerCard(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.secondaryBackground)
            .clickable { onToggle() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = colors.systemOrange,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "免责声明",
                style = typography.subheadline,
                color = colors.secondaryLabel,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "收起" else "展开",
                style = typography.footnote,
                color = colors.tertiaryLabel,
            )
        }
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            // Review fix：AnimatedVisibility content 不提供布局，需外包 Column
            // 否则 Spacer + Text 会被 place 到 (0,0) 导致间距失效
            Column {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = text,
                    style = typography.footnote,
                    color = colors.secondaryLabel,
                )
            }
        }
    }
}
