package com.trae.social.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.theme.LocalSocialColors

/**
 * 通用卡片：圆角 12dp + 微阴影。
 *
 * 对齐 iOS 分组卡片视觉（systemBackground 底色 + 轻投影）。
 *
 * @param cornerRadius 圆角半径，默认 12dp
 * @param elevation 阴影高度，默认 1dp（微阴影）
 * @param content 卡片内容
 */
@Composable
fun SocialCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    elevation: Dp = 1.dp,
    content: @Composable () -> Unit,
) {
    val colors = LocalSocialColors.current
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .shadow(elevation = elevation, shape = shape)
            .background(color = colors.systemBackground, shape = shape),
    ) {
        content()
    }
}
