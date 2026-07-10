package com.trae.social.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.components.GlassBlurContainer
import com.trae.social.designsystem.components.socialClickable
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 底部 Tab 栏配置项：路由、图标、文案。
 */
private data class TabSpec(
    val route: String,
    val icon: ImageVector,
    val label: String,
)

/**
 * 三个主 Tab：首页、时间线、我的。
 *
 * P2 修复：路由字符串改用 [AppRoutes] 常量，避免硬编码。
 */
private val MainTabs = listOf(
    TabSpec(route = AppRoutes.FEED, icon = Icons.Filled.Home, label = "首页"),
    TabSpec(route = AppRoutes.TIMELINE, icon = Icons.Filled.GridView, label = "时间线"),
    TabSpec(route = AppRoutes.PROFILE, icon = Icons.Filled.Person, label = "我的"),
)

/**
 * iOS 26 风格磨砂玻璃底部导航栏。
 *
 * 布局：左侧三个 Tab 等分（weight 1），右侧发布按钮固定宽度。
 * - 选中态：图标与文案 systemBlue，文案下方显示小圆点
 * - 未选中：tertiaryLabel 灰色，无圆点
 * - 高度：56dp + 底部安全区 padding（navigationBars）
 * - 背景：[GlassBlurContainer] 提供磨砂玻璃效果（RISK-6 低端机自动降级）
 *
 * @param currentRoute 当前路由，用于决定选中态
 * @param onTabSelected Tab 点击回调，传入目标路由
 * @param onPublishClick 发布按钮点击回调
 * @param modifier 修饰符
 * @param backgroundLayer 可选的已捕获内容图层，用于真正的背后内容模糊；
 *   由外层通过 `rememberGraphicsLayer()` + `Modifier.drawWithContent` 捕获并传入。
 *   为 null 时回退为纯色半透明（向后兼容）。
 * @param backgroundLayerOffsetY 背景图层的 Y 轴平移偏移（px），用于将内容中
 *   对应底栏位置的区域对齐到容器顶部。
 */
@Composable
fun SocialBottomBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    onPublishClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundLayer: GraphicsLayer? = null,
    backgroundLayerOffsetY: Float = 0f,
) {
    // #45/#56：navigationBarsPadding 移到内部 Row，让 GlassBlurContainer 的玻璃背景层
    // fillMaxSize 延伸到屏幕底部（覆盖系统导航栏区域），仅内容避让导航栏 inset。
    // 原先加在外层会把玻璃层抬到导航栏之上，下方露出后方内容，造成底栏"悬浮/错位"。
    // #2：将外层捕获的内容图层透传给 GlassBlurContainer，实现真正的"模糊背后内容"。
    GlassBlurContainer(
        modifier = modifier.fillMaxWidth(),
        backgroundLayer = backgroundLayer,
        backgroundLayerOffsetY = backgroundLayerOffsetY,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTabs.forEach { tab ->
                TabItem(
                    spec = tab,
                    selected = currentRoute == tab.route,
                    onClick = { onTabSelected(tab.route) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
            Spacer(Modifier.width(16.dp))
            PublishButton(onClick = onPublishClick)
        }
    }
}

/**
 * 单个 Tab 项：图标 + 文案 + 选中圆点。
 *
 * 选中时图标与文案着色 systemBlue，文案下方显示 4dp 圆点；
 * 未选中时着色 tertiaryLabel，圆点隐藏（占位保持布局稳定）。
 */
@Composable
private fun TabItem(
    spec: TabSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val hapticFeedback = LocalHapticFeedback.current
    // #22：选中/未选中颜色平滑过渡，避免瞬间跳变
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.systemBlue else colors.tertiaryLabel,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tabContentColor",
    )
    // #22：选中圆点缩放进场/退场，替代瞬间出现/消失
    val dotScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tabDotScale",
    )

    Column(
        modifier = modifier.socialClickable(onClick = {
            // #3：切换 Tab 时触感反馈（仅切换到新 Tab 时触发，避免重复点击震动）
            if (!selected) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            onClick()
        }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = spec.icon,
            contentDescription = spec.label,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = spec.label,
            style = typography.caption2,
            color = contentColor,
        )
        // #22：选中态小圆点缩放进场（未选中时 scale=0 不可见，保持 4dp 占位避免高度跳动）
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(4.dp)
                .graphicsLayer { scaleX = dotScale; scaleY = dotScale }
                .clip(CircleShape)
                .background(color = colors.systemBlue),
        )
    }
}

/**
 * 发布按钮：与 Tab 栏等高的圆形 FAB，systemBlue 背景 + 白色加号。
 *
 * #26：按压时弹簧缩放反馈（0.92→1.0），释放后回弹，给予明确的触发动效。
 */
@Composable
private fun PublishButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    // #26：自建 InteractionSource 追踪按压状态，驱动缩放动效
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "publishScale",
    )

    Box(
        modifier = modifier
            .size(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(colors.systemBlue)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "发布",
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}
