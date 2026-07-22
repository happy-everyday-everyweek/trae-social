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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.components.CapsuleTab
import com.trae.social.designsystem.theme.LocalReduceMotion
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

/**
 * 缩小飞入信息流动画覆盖层。
 *
 * #157：总时长压到 [PUBLISH_ANIM_DURATION_MS] = 280ms（原 700ms），缓动统一改为
 * [FastOutSlowInEasing]（原 EaseOutBack 弹性过冲）。发布是 deliberate 提交动作，
 * 应 crisp 而非 bouncy，弹性过冲与"确定、可靠"的产品人格不匹配。
 * 减弱动效下使用 [REDUCED_PUBLISH_ANIM_DURATION_MS]，移除位移/缩放仅保留淡入淡出。
 *
 * 三阶段（按比例分配）：
 * - 阶段1（0-PHASE_1_RATIO）：scale 1->0.6 + 上移至屏幕中上部；
 * - 阶段2（PHASE_1_RATIO-PHASE_2_RATIO）：继续缩小 + 淡出 + "发布成功"提示淡入；
 * - 阶段3（PHASE_2_RATIO-1）：成功提示淡出，随后回调 onPublished。
 *
 * 使用 [graphicsLayer] 应用变换。
 * RISK-8 降级：当 imagePath 为空时仅显示成功提示（fade + scale），不显示预览图。
 */
@Composable
private fun PublishFlyInOverlay(
    visible: Boolean,
    imagePath: String?,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    // #157：减弱动效下使用更短时长，让用户尽快看到发布结果
    val durationMs = if (reduceMotion) REDUCED_PUBLISH_ANIM_DURATION_MS
        else PUBLISH_ANIM_DURATION_MS

    LaunchedEffect(visible, reduceMotion) {
        if (visible) {
            progress.snapTo(0f)
            // #157：进度驱动器用 tween + FastOutSlowInEasing（原 EaseOutBack 改为 crisp 缓动）
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMs,
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
        val p = progress.value.coerceIn(0f, 1f)
        val typography = LocalSocialTypography.current
        // #157：所有缓动统一改为 FastOutSlowInEasing（原 EaseOutBack 弹性过冲已移除），
        // 成功提示与缩放均使用 crisp 缓动，与 deliberate 发布动作匹配
        val easing = FastOutSlowInEasing

        // #157：减弱动效下移除缩放动画（仅保留淡入淡出），符合 ReduceMotion.kt
        // 「移除 transform/位移类动画，仅保留 opacity 与 color 过渡」原则
        val scaleVal = if (reduceMotion) 1f
            else when {
                p < PHASE_1_RATIO -> {
                    val frac = easing.transform(p / PHASE_1_RATIO)
                    lerpUnclamped(1f, 0.6f, frac)
                }
                p < PHASE_2_RATIO -> {
                    val frac = easing.transform(
                        (p - PHASE_1_RATIO) / (PHASE_2_RATIO - PHASE_1_RATIO)
                    )
                    lerpUnclamped(0.6f, 0.15f, frac)
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
        // #157：减弱动效下完全移除位移（图片居中淡出），符合 ReduceMotion.kt 原则；
        // 默认用 FastOutSlowInEasing（原 EaseOutBack 改为 crisp 缓动，避免弹性"弹过头再回落"）
        // review 第 5 轮修复：translationY 原用裸 px 值（400/-200），不同屏幕密度下位移距离不一致。
        // 改用 dp 转 px，保证视觉位移在各类密度设备上一致。
        val translationYVal = if (reduceMotion) 0f
            else lerpUnclamped(
                with(density) { 400.dp.toPx() },
                with(density) { (-200).dp.toPx() },
                FastOutSlowInEasing.transform(p),
            )

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
        // #157：成功提示缩放也改用 FastOutSlowInEasing（原 EaseOutBack 移除）；
        // 减弱动效下保持 1f 不缩放
        val successScale = if (reduceMotion) 1f
            else when {
                p < PHASE_1_RATIO -> 0.85f
                p < PHASE_2_RATIO -> {
                    val frac = easing.transform(
                        (p - PHASE_1_RATIO) / (PHASE_2_RATIO - PHASE_1_RATIO)
                    )
                    lerpUnclamped(0.85f, 1f, frac)
                }
                else -> {
                    val frac = easing.transform(
                        (p - PHASE_2_RATIO) / (1f - PHASE_2_RATIO)
                    )
                    lerpUnclamped(1f, 0.8f, frac)
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
            // 始终组合，通过 graphicsLayer alpha 控制可见性，避免 alpha 由 0 变 >0 时布局抖动
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

/**
 * 线性插值（钳制 fraction 至 [0, 1]），适用于线性或不过冲的缓动。
 */
private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction.coerceIn(0f, 1f)

/**
 * 不钳制的线性插值：保留过冲值（>1），
 * 避免 coerceIn 截断而丢失效果。
 */
private fun lerpUnclamped(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

// #157：总时长从 700ms 压到 280ms（< 300ms UI 动画标准），缓动改为 FastOutSlowInEasing
private const val PUBLISH_ANIM_DURATION_MS = 280
// #157：减弱动效下的更短时长，仅保留淡入淡出反馈
private const val REDUCED_PUBLISH_ANIM_DURATION_MS = 150
private const val PHASE_1_RATIO = 3f / 7f
private const val PHASE_2_RATIO = 6f / 7f
