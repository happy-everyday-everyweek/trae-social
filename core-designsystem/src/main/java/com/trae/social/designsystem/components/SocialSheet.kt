package com.trae.social.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.theme.LocalSocialColors

/**
 * 底部弹出 Sheet，包装 Material3 [ModalBottomSheet]。
 *
 * 容器色与圆角对齐 Apple 设计规范：顶部 12dp 圆角，背景使用 systemBackground。
 *
 * @param onDismiss 关闭回调（下滑或点遮罩触发）
 * @param content Sheet 内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalSocialColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = colors.systemBackground,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        content()
    }
}
