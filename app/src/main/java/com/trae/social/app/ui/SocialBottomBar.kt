package com.trae.social.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.components.GlassBlurContainer
import com.trae.social.designsystem.components.socialClickable
import com.trae.social.designsystem.theme.LocalSocialColors

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
 */
@Composable
fun SocialBottomBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    onPublishClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassBlurContainer(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
    val contentColor = if (selected) colors.systemBlue else colors.tertiaryLabel

    Column(
        modifier = modifier.socialClickable(onClick = onClick),
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
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
        // 选中态小圆点（未选中时仍占位 4dp，避免选中/未选中切换时高度跳动）
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(4.dp)
                .then(
                    if (selected) {
                        Modifier.clip(CircleShape).background(color = colors.systemBlue)
                    } else {
                        Modifier
                    }
                ),
        )
    }
}

/**
 * 发布按钮：与 Tab 栏等高的圆形按钮，systemBlue 背景 + 白色加号。
 *
 * 标准 Material FAB 尺寸 56dp，与底部栏内容区等高。
 */
@Composable
private fun PublishButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(colors.systemBlue)
            .socialClickable(onClick = onClick),
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
