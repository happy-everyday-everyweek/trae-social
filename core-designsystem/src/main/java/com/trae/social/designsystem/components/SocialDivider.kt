package com.trae.social.designsystem.components

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.theme.LocalSocialColors

/**
 * 分隔线：1px separator 色。
 *
 * 对齐 iOS separator 视觉，颜色取自主题 separator token。
 *
 * @param thickness 厚度，默认 1dp
 */
@Composable
fun SocialDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
) {
    val colors = LocalSocialColors.current
    HorizontalDivider(
        modifier = modifier,
        thickness = thickness,
        color = colors.separator,
    )
}
