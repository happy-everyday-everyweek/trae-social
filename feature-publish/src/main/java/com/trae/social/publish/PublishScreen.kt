package com.trae.social.publish

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.components.CapsuleTab
import com.trae.social.designsystem.theme.LocalSocialColors
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

    var selectedTab by remember { mutableStateOf(0) } // 0 = 相机, 1 = 编辑器
    var selectedCaptureIndex by remember { mutableStateOf(-1) }
    var showPublishAnimation by remember { mutableStateOf(false) }

    // 监听发布完成事件：触发飞入动画，动画结束后回调 onPublished
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PublishEvent.Published -> {
                    showPublishAnimation = true
                    // 动画时长 400ms，结束后回调
                    scope.launch {
                        kotlinx.coroutines.delay(PUBLISH_ANIM_DURATION_MS.toLong())
                        showPublishAnimation = false
                        onPublished()
                    }
                }
                // IMPL-15：发布失败时显示错误提示，保留输入
                PublishEvent.PublishFailed -> {
                    // isPublishing 已在 ViewModel 中重置，用户可重试
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

            // 相机模式下，captures 非空时显示顶部文本输入
            if (selectedTab == 0 && uiState.captures.isNotEmpty()) {
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
                        onClick = { viewModel.publish() },
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
    }
}

/**
 * 缩小飞入信息流动画覆盖层。
 *
 * 两阶段（总时长 [PUBLISH_ANIM_DURATION_MS]）：
 * - 0-300ms：scale 1→0.3 + 上移至屏幕中上部；
 * - 300-400ms：fade out + scale 至 0.1。
 * 使用 [graphicsLayer] 应用变换；RISK-8 降级：仅 fade + scale。
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

    AnimatedVisibility(
        visible = visible && imagePath != null,
        enter = fadeIn(tween(0)),
        exit = fadeOut(tween(0)),
        modifier = modifier,
    ) {
        val p = progress.value
        // 第一阶段：scale 1 -> 0.3；第二阶段：0.3 -> 0.1
        val scaleVal = if (p < PHASE_1_RATIO) {
            lerp(1f, 0.3f, p / PHASE_1_RATIO)
        } else {
            lerp(0.3f, 0.1f, (p - PHASE_1_RATIO) / (1f - PHASE_1_RATIO))
        }
        // 第二阶段才淡出
        val alphaVal = if (p < PHASE_1_RATIO) 1f else lerp(1f, 0f, (p - PHASE_1_RATIO) / (1f - PHASE_1_RATIO))
        // 上移至屏幕中上部
        val translationYVal = lerp(400f, -200f, p)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = alphaVal * 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
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
        }
    }
}

/**
 * 线性插值。
 */
private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction.coerceIn(0f, 1f)

private const val PUBLISH_ANIM_DURATION_MS = 400
private const val PHASE_1_RATIO = 0.75f // 300/400
