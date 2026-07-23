package com.trae.social.publish

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.components.CapsuleTab
import com.trae.social.designsystem.theme.LocalReduceMotion
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
    // #207 review 修复：publishPhase 由 ViewModel 持有，配置变更（旋转屏）后存活，
    // 替代原 remember { showPublishAnimation }（配置变更时丢失 + Published 事件已消费无法重发）
    val publishPhase by viewModel.publishPhase.collectAsStateWithLifecycle()
    val colors = LocalSocialColors.current
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current

    var selectedTab by remember { mutableStateOf(0) } // 0 = 相机, 1 = 编辑器
    var selectedCaptureIndex by remember { mutableStateOf(-1) }
    val snackbarHostState = remember { SnackbarHostState() }
    // #236：tabs 数组用 remember 保持引用稳定，避免 spread 操作符每次重组生成新 Array
    // 导致 Compose skip 用引用相等性判断失效，CapsuleTab 仍被强制重组。
    val publishTabs = remember { arrayOf("相机", "编辑器") }

    // 监听发布失败事件（一次性 Channel 事件）
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                // IMPL-15：发布失败时显示错误提示 + 重试按钮，保留输入
                PublishEvent.PublishFailed -> {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
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

    // #207 review 修复：动画完成回调由 LaunchedEffect(publishPhase) 驱动。
    // publishPhase 由 ViewModel 持有，配置变更后存活——旋转屏时 LaunchedEffect
    // 重新执行，publishPhase 仍为 ANIMATING，动画重播后正常回调 onPublished。
    // #157：减弱动效下使用更短时长，让用户尽快看到发布结果。
    LaunchedEffect(publishPhase) {
        if (publishPhase == PublishPhase.ANIMATING) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            val duration = if (reduceMotion) REDUCED_PUBLISH_ANIM_DURATION_MS
                else PUBLISH_ANIM_DURATION_MS
            kotlinx.coroutines.delay(duration.toLong())
            viewModel.markPublishAnimationDone()
            onPublished()
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
                    tabs = *publishTabs,
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

            // M6 修复：顶部配文输入仅相机模式显示，编辑器模式在 EditorModeContent 内已有 CaptionInput，
            // 避免编辑器模式下出现两个同步的输入框
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
                        // #9：编辑器模式传入配文，与发布流程共享同一 caption 状态
                        caption = uiState.caption,
                        onCaptionChange = viewModel::updateCaption,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 底部预览栏 + 发布按钮
            // 主 review 第 4 轮修复：publishPhase != IDLE（动画阶段）时隐藏底部栏，
            // 避免用户在飞入动画期间再次点击发布按钮或删除已选图片。
            // 配合 PublishViewModel.publish() 入口的 publishPhase != IDLE 重入保护，
            // 双重确保动画阶段无法触发重复发推。
            if (uiState.captures.isNotEmpty() && publishPhase == PublishPhase.IDLE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // #188：底部控件加 navigationBarsPadding，避免全面屏手势条遮挡发布按钮
                        .navigationBarsPadding()
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
            visible = publishPhase == PublishPhase.ANIMATING,
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
