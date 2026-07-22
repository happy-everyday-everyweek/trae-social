package com.trae.social.publish

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.theme.LocalReduceMotion
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography
import com.trae.social.designsystem.theme.MinTouchTargetSize

/**
 * 闪光灯按钮：点击在 Off/On/Auto 间循环。
 */
@Composable
internal fun FlashToggleButton(
    mode: FlashMode,
    onChange: (FlashMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (mode) {
        FlashMode.OFF -> Icons.Default.FlashOff
        FlashMode.ON -> Icons.Default.FlashOn
        FlashMode.AUTO -> Icons.Default.FlashAuto
    }
    ControlButton(
        icon = icon,
        contentDescription = "闪光灯 ${mode.label}",
        onClick = {
            val next = when (mode) {
                FlashMode.OFF -> FlashMode.ON
                FlashMode.ON -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.OFF
            }
            onChange(next)
        },
        modifier = modifier,
    )
}

/**
 * 网格线开关按钮。
 */
@Composable
internal fun GridToggleButton(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = if (enabled) Icons.Filled.GridOn else Icons.Filled.GridOff
    ControlButton(
        icon = icon,
        contentDescription = if (enabled) "关闭网格线" else "开启网格线",
        onClick = onToggle,
        modifier = modifier,
    )
}

/**
 * 圆形控制按钮（顶部）。
 *
 * #27：添加半透明白色边框，避免深色模式下相机预览较暗时"黑叠黑"导致按钮不可见。
 */
@Composable
internal fun ControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(MinTouchTargetSize)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f))
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.2f), shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 底部相机控制栏：比例切换 + 拍照按钮。
 *
 * #36：拍照按钮支持长按连拍。
 */
@Composable
internal fun BottomCameraBar(
    ratio: CaptureRatio,
    onRatioChange: (CaptureRatio) -> Unit,
    onShutter: () -> Unit,
    onBurstShutter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    Row(
        modifier = modifier
            // #188：底部控件加 navigationBarsPadding，避免全面屏手势条遮挡快门与比例按钮
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 左侧：比例切换
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CaptureRatio.values().forEach { r ->
                val selected = r == ratio
                val bgColor = if (selected) colors.systemBlue else Color.Black.copy(alpha = 0.6f)
                val textColor = if (selected) Color.White else Color.White.copy(alpha = 0.7f)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(bgColor)
                        // #27：未选中时添加半透明边框，深色模式下提升可见性
                        .then(
                            if (!selected) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(50),
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onRatioChange(r) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = r.label,
                        style = typography.caption1,
                        color = textColor,
                    )
                }
            }
        }

        // 中间：拍照按钮
        ShutterButton(
            onClick = onShutter,
            // #36：长按触发连拍
            onLongClick = onBurstShutter,
        )

        // 右侧占位以保持拍照按钮水平居中
        Spacer(Modifier.width(48.dp))
    }
}

/**
 * 拍照按钮：72dp 圆形，白色边框 + systemBlue 内圈。
 *
 * #3/#36：按下时弹簧缩放反馈（0.85→1.0）+ 快门触感，给予明确的拍摄触发动效。
 * #36：支持长按连拍（onLongClick），单击拍照（onClick）。
 */
@Composable
internal fun ShutterButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val hapticFeedback = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    // #3：自建 InteractionSource 追踪按压状态，驱动缩放动效
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // review 第 5 轮修复：pointerInput(Unit) 只在首次组合启动一次，捕获的 onClick/onLongClick
    // 是按值捕获的局部 lambda（内部又捕获 ratio）。用户切换拍照比例后比例变化使上层重组，
    // 但 pointerInput 不重启，仍持有旧 lambda，导致 SQUARE 模式下成片不做正方形裁剪。
    // 用 rememberUpdatedState 始终读取最新回调，避免捕获过期值。
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    // #200：按压弹簧——
    // - 默认：NoBouncy + StiffnessMedium，快门是高频操作不需要 overshoot；
    //   原 MediumBouncy 让快门每次按都晃几下，连拍时视觉抖动严重。
    // - 减弱动效：tween(120, FastOutSlowInEasing)，按压反馈不可省（确保用户知道按下去了），
    //   但要短而平。
    val scaleSpec = if (reduceMotion) {
        tween<Float>(durationMillis = 120, easing = FastOutSlowInEasing)
    } else {
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )
    }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = scaleSpec,
        label = "shutterScale",
    )
    Box(
        modifier = modifier
            .size(72.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .border(width = 4.dp, color = Color.White, shape = CircleShape)
            .background(Color.Transparent)
            // #36：使用 detectTapGestures 替代 clickable，支持单击拍照 + 长按连拍
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        // #3：手动发射按压状态，驱动缩放动画
                        // CI 修复：PressInteraction.Press 构造函数需要 pressPosition 参数
                        val press = PressInteraction.Press(Offset.Zero)
                        interactionSource.emit(press)
                        val released = tryAwaitRelease()
                        if (released) {
                            interactionSource.emit(PressInteraction.Release(press))
                        } else {
                            interactionSource.emit(PressInteraction.Cancel(press))
                        }
                    },
                    onTap = {
                        // #3：快门触感反馈
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnClick()
                    },
                    onLongPress = {
                        // #36：长按触发连拍 + 触感反馈
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnLongClick()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.systemBlue),
        )
    }
}

/**
 * 权限缺失时显示的请求卡片。
 */
@Composable
internal fun PermissionRequestCard(
    onOpenSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    Box(
        modifier = modifier.background(colors.systemBackground),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.padding(horizontal = 32.dp),
            colors = CardDefaults.cardColors(containerColor = colors.secondaryBackground),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "需要相机权限",
                    style = typography.headline,
                    color = colors.label,
                )
                Text(
                    text = "请在设置中开启相机权限以使用拍照功能",
                    style = typography.subheadline,
                    color = colors.secondaryLabel,
                )
                ActionButton(
                    text = "前往设置",
                    onClick = onOpenSettings,
                )
                Spacer(Modifier.height(4.dp))
                ActionButton(
                    text = "再次请求",
                    onClick = onRequestPermission,
                )
            }
        }
    }
}
