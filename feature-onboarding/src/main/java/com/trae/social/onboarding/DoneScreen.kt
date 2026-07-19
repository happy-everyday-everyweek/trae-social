package com.trae.social.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography
import kotlinx.coroutines.delay

/**
 * 引导完成页（SubTask 9.5 的 done 路由）。
 *
 * 展示配置已保存的成功状态，并提供"进入应用"按钮触发 [onCompleted]。
 * 同时在显示后短暂延迟自动回调 [onCompleted]，避免用户卡在此页。
 *
 * @param onCompleted 进入主界面回调（由 app 层标记完成并切换至主框架）
 * @param autoDismissMs 自动进入主界面的延迟（默认 1500ms）
 */
@Composable
fun DoneScreen(
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMs: Long = 1500L,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    // 防止自动延迟与手动按钮重复触发 onCompleted：第一次调用后置 dismissed，
    // 后续路径（LaunchedEffect delay 到期或再次点击）直接短路返回。
    var dismissed by remember { mutableStateOf(false) }
    val safeOnCompleted: () -> Unit = {
        if (!dismissed) {
            dismissed = true
            onCompleted()
        }
    }

    // 显示一段时间后自动进入主界面（用户也可点击按钮立即进入）
    LaunchedEffect(autoDismissMs) {
        delay(autoDismissMs)
        safeOnCompleted()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.systemBackground)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(colors.systemGreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "完成",
                tint = Color.White,
                modifier = Modifier.size(48.dp),
            )
        }

        Spacer(Modifier.size(24.dp))

        Text(
            text = "配置完成",
            style = typography.title1,
            color = colors.label,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.size(8.dp))

        Text(
            text = "已保存配置并触发冷启动内容填充，即将进入主界面",
            style = typography.subheadline,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.size(32.dp))

        ActionButton(
            text = "进入应用",
            onClick = safeOnCompleted,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
