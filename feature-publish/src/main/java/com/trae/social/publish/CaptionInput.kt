package com.trae.social.publish

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trae.social.core.data.TweetLimits
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 图片顶部文本输入（SubTask 14.5）。
 *
 * 在相机模式下 captures 非空时显示于顶部：
 * - placeholder "说点什么..."；
 * - 字数计数 "123/280"（右下角 caption）；
 * - 超过 280 字符禁止输入（由 ViewModel 的 [PublishViewModel.updateCaption] 截断）。
 *
 * @param text 当前文本
 * @param onTextChanged 文本变更回调
 */
@Composable
fun CaptionInput(
    text: String,
    onTextChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val count = text.length
    val nearLimit = count >= TweetLimits.MAX_CAPTION_LENGTH - 20

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.secondaryBackground.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        TextField(
            value = text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "说点什么...",
                    style = typography.body,
                    color = colors.tertiaryLabel,
                )
            },
            textStyle = typography.body.copy(color = colors.label),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = colors.systemBlue,
            ),
            minLines = 1,
            maxLines = 4,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        ) {
            Text(
                text = "$count/${TweetLimits.MAX_CAPTION_LENGTH}",
                style = typography.caption1,
                color = if (nearLimit) colors.systemRed else colors.tertiaryLabel,
            )
        }
    }
}
