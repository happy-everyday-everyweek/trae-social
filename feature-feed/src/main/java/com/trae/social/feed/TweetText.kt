package com.trae.social.feed

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.trae.social.core.data.TweetLimits
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialSpacing
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 推文文本：超过 280 字符折叠，附"展开全文"/"收起"切换。
 *
 * #323：从原 TweetCard.kt 拆分到此文件，TweetCard.kt 作为入口仅保留编排逻辑。
 */
@Composable
internal fun TweetText(
    text: String,
    labelColor: Color,
) {
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    // #33：动态字号边界处理——读取系统 fontScale，超大字号（>1.3f）时对正文加
    // maxLines + ellipsis 防止正文撑爆屏幕（sp 自身随 fontScale 缩放，此处仅做裁剪兜底）。
    val fontScale = LocalConfiguration.current.fontScale
    val bodyMaxLines = if (fontScale > 1.3f) 8 else Int.MAX_VALUE
    val limit = TweetLimits.MAX_TWEET_LENGTH
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val needCollapse = text.length > limit
    val displayText = if (needCollapse && !expanded) text.take(limit) else text

    Column(
        modifier = Modifier.animateContentSize(animationSpec = tween(durationMillis = 250)),
    ) {
        Text(
            text = displayText,
            style = typography.body,
            color = labelColor,
            // #33：超大字号时限制最大行数并配合 ellipsis，避免长正文在 1.5x+ 字号下撑爆屏幕
            maxLines = bodyMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (needCollapse) {
            Spacer(Modifier.height(spacing.xs))
            // #24：展开/收起文案用 AnimatedContent 过渡，同一时刻仅渲染一个文案，
            // 避免 Crossfade 过渡期内两个可点击 Text 同时存在
            AnimatedContent(
                targetState = expanded,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "expand_toggle",
            ) { isExpanded ->
                Text(
                    text = if (isExpanded) "收起" else "展开全文",
                    style = typography.caption1.copy(fontWeight = FontWeight.SemiBold),
                    color = LocalSocialColors.current.systemBlue,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
    }
}
