package com.trae.social.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.theme.LocalReduceMotion
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 胶囊形横向 Tab 组件。
 *
 * 选中项以 systemBlue 胶囊高亮、白色文字；未选中项为次级背景、label 色。
 * 支持横向滚动以容纳较多 Tab 项。
 *
 * @param tabs Tab 文案列表
 * @param selectedIndex 当前选中索引
 * @param onTabSelected 选中回调
 */
@Composable
fun CapsuleTab(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val reduceMotion = LocalReduceMotion.current
    // #204：色值过渡 spec——
    // - 默认：NoBouncy + StiffnessMediumLow（≈200ms），色彩柔顺切换；原先未指定 stiffness
    //   回落到 StiffnessMedium(1500)，对色值过渡过慢、肉眼可感"迟滞"。
    // - 减弱动效：tween(150, FastOutSlowInEasing)，仅保留色值过渡、移除任何物理感
    val colorSpec = if (reduceMotion) {
        tween<Color>(durationMillis = 150, easing = FastOutSlowInEasing)
    } else {
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
    }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            // #22：胶囊背景色与文字色平滑过渡，避免瞬间跳变
            val bgColor by animateColorAsState(
                targetValue = if (selected) colors.systemBlue else colors.secondaryBackground,
                animationSpec = colorSpec,
                label = "capsuleBg",
            )
            val contentColor by animateColorAsState(
                targetValue = if (selected) Color.White else colors.label,
                animationSpec = colorSpec,
                label = "capsuleContent",
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bgColor)
                    .socialClickable { onTabSelected(index) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab,
                    style = typography.callout,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
