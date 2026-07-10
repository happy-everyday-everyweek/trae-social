package com.trae.social.designsystem.components

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.theme.LocalSocialColors

/**
 * 分隔线：0.5px separator 色。
 *
 * 对齐 iOS separator 视觉，颜色取自主题 separator token。
 * #32：全局统一厚度为 0.5dp（对齐 iOS separator），不再各处自定义。
 *
 * @param thickness 厚度，默认 0.5dp
 */
@Composable
fun SocialDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 0.5.dp,
) {
    val colors = LocalSocialColors.current
    HorizontalDivider(
        modifier = modifier,
        thickness = thickness,
        color = colors.separator,
    )
}
