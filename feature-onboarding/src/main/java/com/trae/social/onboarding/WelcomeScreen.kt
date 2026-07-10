package com.trae.social.onboarding

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography

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
                .height(220.dp),
        )

        Text(
            text = "欢迎使用 Trae Social",
            style = typography.largeTitle,
            color = colors.label,
            textAlign = TextAlign.Center,
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BulletPoint(text = "由 LLM 驱动的高拟真社交生态")
            BulletPoint(text = "200+ 虚拟账号与你实时互动")
            BulletPoint(text = "完全本地运行，数据私密可控")
        }

        Spacer(Modifier.height(8.dp))

        DisclaimerCard(
            text = "本应用中的虚拟账号内容由 AI 生成，仅供演示与体验，不代表真实人物观点",
        )

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
 * 顶部几何插画：圆形 + 矩形组合，systemBlue 渐变色。
 *
 * 完全由 Compose 绘制，无图片资源依赖。
 */
@Composable
private fun WelcomeIllustration(modifier: Modifier = Modifier) {
    val colors = LocalSocialColors.current
    val blue = colors.systemBlue
    val purple = colors.systemPurple
    val green = colors.systemGreen

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // 大圆：渐变背景
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(blue, purple),
                    ),
                ),
        )
        // 小圆：左上点缀
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 32.dp, top = 24.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(green.copy(alpha = 0.85f)),
        )
        // 小圆：右下点缀
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 28.dp, bottom = 20.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(blue.copy(alpha = 0.7f)),
        )
        // 中央矩形：模拟卡片
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 60.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.9f)),
        )
    }
}

/**
 * 单条要点行：圆形项目符号 + 文案。
 */
@Composable
private fun BulletPoint(text: String) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(colors.systemBlue),
        )
        Text(
            text = text,
            style = typography.body,
            color = colors.label,
        )
    }
}

/**
 * 免责声明卡片（RISK-12）。
 *
 * 浅色背景 + info 图标 + 提示文案，强调虚拟内容性质。
 */
@Composable
private fun DisclaimerCard(text: String) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.secondaryBackground)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = colors.systemOrange,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = typography.footnote,
            color = colors.secondaryLabel,
        )
    }
}
