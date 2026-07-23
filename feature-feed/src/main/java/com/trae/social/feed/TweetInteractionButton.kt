package com.trae.social.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.components.socialClickable
import com.trae.social.designsystem.theme.LocalReduceMotion
import com.trae.social.designsystem.theme.LocalSocialSpacing
import com.trae.social.designsystem.theme.LocalSocialTypography
import com.trae.social.designsystem.theme.minTouchTarget
import java.util.Locale

/**
 * "万" 显示阈值：计数 >= 此值时格式化为 "x.x万"（#285：与 ProfileUtils 对齐）。
 */
private const val WAN_THRESHOLD = 10000

/**
 * 互动按钮：图标 + 计数（count 为 null 时不显示数字）。
 *
 * #3：点赞（[bounceWhenActive] + [active]）在 false→true 跳变时做 overshoot 弹跳，
 * 营造心跳反馈；[hapticOnPress] 在按下时触发触感反馈，提升社交鲜活感。
 *
 * #323：从原 TweetCard.kt 拆分到此文件，TweetCard.kt 作为入口仅保留编排逻辑。
 */
@Composable
internal fun InteractionButton(
    icon: ImageVector,
    count: Int?,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
    bounceWhenActive: Boolean = false,
    active: Boolean = false,
    hapticOnPress: Boolean = false,
) {
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    val hapticFeedback = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    // #3/#201：仅在 false→true 跳变时弹跳，hasObserved 防止首帧（已点赞项）误触动画
    // - 原先 snapTo(0.6f) → animateTo(1f, MediumBouncy+StiffnessMedium) 起点过低（缩到一半）、
    //   wobble 3+ 次，整个点赞反馈拖沓且过大，与社交帖子"轻盈点赞"调性不符。
    // - 调整为 snapTo(0.8f) → LowBouncy+StiffnessMediumLow ≈ 300ms 收束，一次柔和 overshoot
    //   即稳。Emil "nothing appears from nothing" + Apple "damping 0.8 仅在带速度时"原则：
    //   用户点击本身是带速度的输入，所以保留一次轻 overshoot。
    // - 减弱动效：tween(150, FastOutSlowInEasing) 直接到 1f，无 overshoot，
    //   仍能感知到"图标被按了一下"。
    val scale = remember { Animatable(1f) }
    var hasObserved by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (bounceWhenActive && active && hasObserved) {
            // 两个分支仅 spec 不同，抽出共有的 snap + animateTo 以提升可读性
            val spec = if (reduceMotion) {
                tween<Float>(
                    durationMillis = 150,
                    easing = FastOutSlowInEasing,
                )
            } else {
                spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            }
            scale.snapTo(0.8f)
            scale.animateTo(targetValue = 1f, animationSpec = spec)
        }
        hasObserved = true
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .minTouchTarget()
            // #21：水波纹按压反馈
            .socialClickable(onClick = {
                if (hapticOnPress) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                onClick()
            })
            .padding(horizontal = spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(spacing.xs))
            Text(
                text = formatCount(count),
                style = typography.caption1,
                color = tint,
            )
        }
    }
}

/**
 * 数量格式化：超 1 万显示为 "1.2万"。
 *
 * 显式指定 [Locale.ROOT]：避免在某些语言环境（如德语/法语区）下默认 Locale
 * 使用逗号作为小数分隔符，导致输出 "1,2万" 与中文语境不一致。
 */
internal fun formatCount(count: Int): String {
    return if (count >= WAN_THRESHOLD) {
        val wan = count / WAN_THRESHOLD.toDouble()
        String.format(Locale.ROOT, "%.1f万", wan)
    } else {
        count.toString()
    }
}
