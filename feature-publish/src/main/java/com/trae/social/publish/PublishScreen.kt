package com.trae.social.publish

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.components.CapsuleTab
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography
import kotlinx.coroutines.launch

/**
 * 发布屏幕（Task 14）。
 *
 * 顶部胶囊形 Tab（相机/编辑器）+ 内容区 AnimatedContent 淡入淡出 + 右上角关闭按钮。
 * 相机模式：CameraX 取景 + 比例/闪光灯/前后摄 + 拍照落盘 + 顶部文本输入 + 底部实时预览。
 * 编辑器模式：相册选图 + 裁剪 + 滤镜。
 * 发布：缩小飞入动画（RISK-8 可降级）→ 落库 + 触发 AI 互动 → [onPublished]。
 *
 * @param onPublished 发布完成回调（导航回首页，新推文已置顶）
 * @param onClose 关闭回调（右上角关闭按钮）
 */
@Composable
fun PublishScreen(
    modifier: Modifier = Modifier,
    onPublished: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val viewModel: PublishViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalSocialColors.current
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    var selectedTab by remember { mutableStateOf(0) } // 0 = 相机, 1 = 编辑器
    var selectedCaptureIndex by remember { mutableStateOf(-1) }
    var showPublishAnimation by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 监听发布完成事件：触发飞入动画，动画结束后回调 onPublished
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PublishEvent.Published -> {
                    showPublishAnimation = true
                    // 动画时长 700ms（三阶段），结束后回调
                    scope.launch {
                        kotlinx.coroutines.delay(PUBLISH_ANIM_DURATION_MS.toLong())
                        showPublishAnimation = false
                        onPublished()
                    }
                }
                // IMPL-15：发布失败时显示错误提示 + 重试按钮，保留输入
                PublishEvent.PublishFailed -> {
                    // isPublishing 已在 ViewModel 中重置，用户可重试
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "发布失败，请重试",
                            actionLabel = "重试",
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.publish()
                        }
                    }
                }
            }
        }
    }

    Box(modifier = modifier.background(colors.systemBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏：CapsuleTab + 关闭按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CapsuleTab(
                    tabs = listOf("相机", "编辑器"),
                    selectedIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(colors.secondaryBackground)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = colors.label,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // captures 非空时显示顶部配文输入（相机模式与编辑器模式均可用）
            if (uiState.captures.isNotEmpty()) {
                CaptionInput(
                    text = uiState.caption,
                    onTextChanged = viewModel::updateCaption,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // 内容区：相机 / 编辑器 淡入淡出
            AnimatedContent(
                targetState = selectedTab,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    fadeIn(tween(200)).togetherWith(fadeOut(tween(200)))
                },
                label = "publish-mode",
            ) { tab ->
                when (tab) {
                    0 -> CameraModeContent(
                        ratio = uiState.selectedRatio,
                        flashMode = uiState.flashMode,
                        onRatioChange = viewModel::setRatio,
                        onFlashModeChange = viewModel::setFlashMode,
                        onCapture = { path ->
                            viewModel.addCapture(path)
                            selectedCaptureIndex = (uiState.captures.size).coerceAtMost(
                                PublishViewModel.MAX_CAPTURES - 1
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    1 -> EditorModeContent(
                        onEditComplete = { path ->
                            viewModel.addCapture(path)
                            selectedCaptureIndex = (uiState.captures.size).coerceAtMost(
                                PublishViewModel.MAX_CAPTURES - 1
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 底部预览栏 + 发布按钮
            if (uiState.captures.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CapturePreviewBar(
                        captures = uiState.captures,
                        selectedIndex = selectedCaptureIndex.coerceAtLeast(-1),
                        onItemSelected = { selectedCaptureIndex = it },
                        onItemRemoved = { index ->
                            viewModel.removeCapture(index)
                            // IMPL-37：删除项后修正选中索引，避免越界或错位
                            when {
                                index < selectedCaptureIndex -> selectedCaptureIndex--
                                index == selectedCaptureIndex -> selectedCaptureIndex = -1
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton(
                        text = "发布",
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.publish()
                        },
                        enabled = !uiState.isPublishing,
                    )
                }
            }
        }

        // 缩小飞入动画覆盖层（SubTask 14.4）
        PublishFlyInOverlay(
            visible = showPublishAnimation,
            imagePath = uiState.captures.firstOrNull(),
            modifier = Modifier.fillMaxSize(),
        )

        // IMPL-15：发布失败时的错误提示
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * 缩小飞入信息流动画覆盖层。
 *
 * 三阶段（总时长 [PUBLISH_ANIM_DURATION_MS] = 700ms）：
 * - 阶段1（0-300ms）：scale 1->0.6 + 上移至屏幕中上部；
 * - 阶段2（300-600ms）：继续缩小 + 淡出 + "发布成功"提示淡入；
 * - 阶段3（600-700ms）：成功提示淡出，随后回调 onPublished。
 *
 * 使用 [graphicsLayer] 应用变换；缩放使用 [EaseOutBackEasing] 弹性缓动替代线性插值。
 * RISK-8 降级：当 imagePath 为空时仅显示成功提示（fade + scale），不显示预览图。
 */
@Composable
private fun PublishFlyInOverlay(
    visible: Boolean,
    imagePath: String?,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = PUBLISH_ANIM_DURATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            progress.snapTo(0f)
        }
    }

    // 不再要求 imagePath != null：降级模式下也显示成功提示
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(0)),
        exit = fadeOut(tween(0)),
        modifier = modifier,
    ) {
        val p = progress.value
        val typography = LocalSocialTypography.current

        // 缩放：阶段1用 EaseOutBack 弹性缓动 1->0.6，阶段2线性 0.6->0.15
        val scaleVal = when {
            p < PHASE_1_RATIO -> {
                val frac = EaseOutBackEasing.transform(p / PHASE_1_RATIO)
                lerp(1f, 0.6f, frac)
            }
            p < PHASE_2_RATIO -> {
                val frac = (p - PHASE_1_RATIO) / (PHASE_2_RATIO - PHASE_1_RATIO)
                lerp(0.6f, 0.15f, frac)
            }
            else -> 0.15f
        }
        // 图片透明度：阶段1保持 1，阶段2淡出至 0
        val alphaVal = when {
            p < PHASE_1_RATIO -> 1f
            p < PHASE_2_RATIO -> {
                val frac = (p - PHASE_1_RATIO) / (PHASE_2_RATIO - PHASE_1_RATIO)
                lerp(1f, 0f, frac)
            }
            else -> 0f
        }
        // 上移至屏幕中上部（使用 FastOutSlowInEasing 缓动）
        val translationYVal = lerp(400f, -200f, FastOutSlowInEasing.transform(p))

        // 成功提示：阶段2淡入，阶段3淡出
        val successAlpha = when {
            p < PHASE_1_RATIO -> 0f
            p < PHASE_2_RATIO -> {
                val frac = (p - PHASE_1_RATIO) / (PHASE_2_RATIO - PHASE_1_RATIO)
                lerp(0f, 1f, frac)
            }
            else -> {
                val frac = (p - PHASE_2_RATIO) / (1f - PHASE_2_RATIO)
                lerp(1f, 0f, frac)
            }
        }
        // 成功提示缩放：阶段2用 EaseOutBack 弹性放大 0.3->1，阶段3略缩小
        val successScale = when {
            p < PHASE_1_RATIO -> 0.3f
            p < PHASE_2_RATIO -> {
                val frac = EaseOutBackEasing.transform(
                    (p - PHASE_1_RATIO) / (PHASE_2_RATIO - PHASE_1_RATIO)
                )
                lerp(0.3f, 1f, frac)
            }
            else -> {
                val frac = (p - PHASE_2_RATIO) / (1f - PHASE_2_RATIO)
                lerp(1f, 0.8f, frac)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = maxOf(alphaVal, successAlpha) * 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            // 预览图（仅当有路径时显示）
            val path = imagePath
            if (path != null) {
                AsyncImage(
                    model = path,
                    contentDescription = "发布中",
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .graphicsLayer {
                            scaleX = scaleVal
                            scaleY = scaleVal
                            alpha = alphaVal
                            translationY = translationYVal
                        },
                )
            }
            // 成功提示：checkmark + 文本（降级模式下也显示，保证最小成功反馈）
            if (successAlpha > 0f) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        alpha = successAlpha
                        scaleX = successScale
                        scaleY = successScale
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "发布成功",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "发布成功",
                        color = Color.White,
                        style = typography.body,
                    )
                }
            }
        }
    }
}

/**
 * 线性插值。
 */
private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction.coerceIn(0f, 1f)

/**
 * EaseOutBack 缓动：末尾带轻微过冲，让缩放更有弹性。
 */
private val EaseOutBackEasing = Easing { fraction ->
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val t = fraction - 1f
    1f + c3 * t * t * t + c1 * t * t
}

private const val PUBLISH_ANIM_DURATION_MS = 700
private const val PHASE_1_RATIO = 3f / 7f // 300/700
private const val PHASE_2_RATIO = 6f / 7f // 600/700
