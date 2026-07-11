package com.trae.social.publish

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography
import timber.log.Timber
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 相机模式内容（SubTask 14.2）。
 *
 * 全屏 CameraX 取景 + 比例切换 + 拍照 + 前后摄 + 闪光灯 + 权限处理。
 *
 * @param ratio 当前拍照比例
 * @param flashMode 当前闪光灯模式
 * @param onRatioChange 比例切换回调
 * @param onFlashModeChange 闪光灯切换回调
 * @param onCapture 拍照成功回调（传入落盘文件绝对路径）
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraModeContent(
    ratio: CaptureRatio,
    flashMode: FlashMode,
    onRatioChange: (CaptureRatio) -> Unit,
    onFlashModeChange: (FlashMode) -> Unit,
    onCapture: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var showGrid by remember { mutableStateOf(false) }
    // 水平仪：设备 roll 倾斜角（度），0 表示竖直水平
    var rollDegrees by remember { mutableStateOf(0f) }
    // 点击对焦动画
    var focusOffset by remember { mutableStateOf<Offset?>(null) }
    val focusAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 对焦动画 Job：连续点击时取消上一次动画，避免竞态
    var focusJob by remember { mutableStateOf<Job?>(null) }
    val hasCameraPermission = cameraPermission.status.isGranted

    // 加速度计传感器用于水平仪（假设竖屏拍摄）
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    // P1-1：避免主线程 ANR——ProcessCameraProvider.getInstance(context).get() 是阻塞调用，
    // 切换到 IO 线程获取。
    // P1-2：将权限状态加入 LaunchedEffect key，权限授予后自动重新绑定相机，避免黑屏。
    LaunchedEffect(ratio, lensFacing, hasCameraPermission) {
        if (!hasCameraPermission) return@LaunchedEffect
        // P1-1：在 IO 线程执行阻塞的 Future.get()
        val provider = withContext(Dispatchers.IO) {
            runCatching { ProcessCameraProvider.getInstance(context).get() }.getOrNull()
        } ?: return@LaunchedEffect
        cameraProvider = provider
        runCatching {
            provider.unbindAll()
            val preview = Preview.Builder()
                .setTargetAspectRatio(ratio.toCameraXAspectRatio())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setTargetAspectRatio(ratio.toCameraXAspectRatio())
                .setFlashMode(flashMode.toCameraXFlash())
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            // 保留 Camera 实例，用于点击对焦/测光
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        }.onFailure { Timber.w(it, "CameraX 绑定失败 ratio=%s", ratio) }
    }

    // 闪光灯变化时即时更新 ImageCapture 配置
    LaunchedEffect(flashMode) {
        imageCapture?.setFlashMode(flashMode.toCameraXFlash())
    }

    // 注册/注销加速度计监听，驱动水平仪角度
    // 传感器注册需相机权限：无权限或无传感器时不注册，避免无谓监听
    DisposableEffect(hasCameraPermission, accelerometer) {
        if (!hasCameraPermission || accelerometer == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    // roll = 设备绕取景轴的倾斜角；竖屏下用 x / y 计算
                    val x = event.values[0]
                    val y = event.values[1]
                    rollDegrees = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    // P1-3：离开组合时解绑相机 + 释放执行器，避免 Tab 切换后相机传感器持续占用
    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            runCatching { executor.shutdown() }
        }
    }

    val onShutter: () -> Unit = {
        capturePhoto(context, imageCapture, executor) { path ->
            if (path != null) {
                // IMPL-35：1:1 比例时中心裁剪为正方形
                val finalPath = if (ratio == CaptureRatio.SQUARE) {
                    cropToSquare(path) ?: path
                } else {
                    path
                }
                onCapture(finalPath)
            }
        }
    }
    val onSwitchCamera: () -> Unit = {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (cameraPermission.status.isGranted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(camera) {
                        detectTapGestures { offset ->
                            val cam = camera ?: return@detectTapGestures
                            // 通过 PreviewView 的测光点工厂将点击坐标映射为传感器测光点
                            val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                            val action = FocusMeteringAction.Builder(point).build()
                            cam.cameraControl.startFocusAndMetering(action)
                            // 对焦框淡入淡出动画：取消上一次动画避免竞态
                            focusJob?.cancel()
                            focusJob = scope.launch {
                                focusOffset = offset
                                focusAlpha.snapTo(0f)
                                focusAlpha.animateTo(1f, tween(80))
                                focusAlpha.animateTo(0f, tween(700))
                                focusOffset = null
                            }
                        }
                    },
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )
                // IMPL-35：1:1 比例叠加非透明黑色遮罩，正方形区域外不可见
                if (ratio == CaptureRatio.SQUARE) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 上方黑色条带（占剩余空间的上半部分）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color.Black),
                        )
                        // 中心正方形：全宽 + 1:1 比例，无背景色（透出预览）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .border(width = 1.dp, color = Color.White.copy(alpha = 0.6f)),
                        )
                        // 下方黑色条带（占剩余空间的下半部分）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color.Black),
                        )
                    }
                }
                // 构图辅助覆盖层：网格线 + 水平仪 + 对焦框
                // 置于黑色遮罩之后，避免 1:1 比例时对焦框/网格被遮罩遮挡
                CompositionAidsOverlay(
                    showGrid = showGrid,
                    rollDegrees = rollDegrees,
                    hasSensorData = accelerometer != null,
                    focusOffset = focusOffset,
                    focusAlpha = focusAlpha.value,
                    ratio = ratio,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            PermissionRequestCard(
                onOpenSettings = { openAppSettings(context) },
                onRequestPermission = { cameraPermission.launchPermissionRequest() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 顶部控制栏：闪光灯 + 网格 + 前后摄
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FlashToggleButton(
                    mode = flashMode,
                    onChange = onFlashModeChange,
                )
                GridToggleButton(
                    enabled = showGrid,
                    onToggle = { showGrid = !showGrid },
                )
            }
            ControlButton(
                icon = Icons.Default.Cameraswitch,
                contentDescription = "切换前后摄",
                onClick = onSwitchCamera,
            )
        }

        // 底部控制栏：比例切换 + 拍照按钮
        BottomCameraBar(
            ratio = ratio,
            onRatioChange = onRatioChange,
            onShutter = onShutter,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }
}

/**
 * 闪光灯按钮：点击在 Off/On/Auto 间循环。
 */
@Composable
private fun FlashToggleButton(
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
private fun GridToggleButton(
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
 * 构图辅助覆盖层：九宫格网格线 + 水平仪 + 点击对焦框。
 *
 * - 网格线：将可见预览区域三等分，绘制 2 横 2 竖半透明白线（三分法）；
 * - 水平仪：中心水平指示线，随设备倾斜反向旋转，水平时变绿；
 * - 对焦框：点击位置短暂显示方框并淡出。
 */
@Composable
private fun CompositionAidsOverlay(
    showGrid: Boolean,
    rollDegrees: Float,
    hasSensorData: Boolean,
    focusOffset: Offset?,
    focusAlpha: Float,
    ratio: CaptureRatio,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val canvasW = size.width
        val canvasH = size.height
        // 可见预览区域：1:1 时为中心正方形，其余为整块画布
        val region = if (ratio == CaptureRatio.SQUARE) {
            val side = canvasW
            val top = (canvasH - side) / 2f
            Rect(0f, top, canvasW, top + side)
        } else {
            Rect(0f, 0f, canvasW, canvasH)
        }

        // 九宫格构图线（三分法）
        if (showGrid) {
            val gridColor = Color.White.copy(alpha = 0.4f)
            val strokeW = 1.dp.toPx()
            val thirdW = region.width / 3f
            val thirdH = region.height / 3f
            drawLine(
                gridColor,
                start = Offset(region.left + thirdW, region.top),
                end = Offset(region.left + thirdW, region.bottom),
                strokeWidth = strokeW,
            )
            drawLine(
                gridColor,
                start = Offset(region.left + 2 * thirdW, region.top),
                end = Offset(region.left + 2 * thirdW, region.bottom),
                strokeWidth = strokeW,
            )
            drawLine(
                gridColor,
                start = Offset(region.left, region.top + thirdH),
                end = Offset(region.right, region.top + thirdH),
                strokeWidth = strokeW,
            )
            drawLine(
                gridColor,
                start = Offset(region.left, region.top + 2 * thirdH),
                end = Offset(region.right, region.top + 2 * thirdH),
                strokeWidth = strokeW,
            )
        }

        // 水平仪：中心水平线，反向旋转以指示真实水平方向，水平时变绿
        // 无传感器数据（如模拟器）时不绘制，避免恒为 0 度导致的假"已水平"提示
        if (hasSensorData) {
            val isLevel = abs(rollDegrees) < LEVEL_THRESHOLD_DEG
            val levelColor = if (isLevel) Color.Green.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.7f)
            val center = region.center
            val halfLen = 40.dp.toPx()
            rotate(degrees = -rollDegrees, pivot = center) {
                drawLine(
                    color = levelColor,
                    start = Offset(center.x - halfLen, center.y),
                    end = Offset(center.x + halfLen, center.y),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }

        // 点击对焦框：在点击位置绘制淡入淡出方框
        val fo = focusOffset
        if (fo != null && focusAlpha > 0f) {
            val ringSize = 80.dp.toPx()
            drawRect(
                color = Color.White.copy(alpha = focusAlpha),
                topLeft = Offset(fo.x - ringSize / 2f, fo.y - ringSize / 2f),
                size = Size(ringSize, ringSize),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/**
 * 圆形控制按钮（顶部）。
 */
@Composable
private fun ControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
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
 */
@Composable
private fun BottomCameraBar(
    ratio: CaptureRatio,
    onRatioChange: (CaptureRatio) -> Unit,
    onShutter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 24.dp),
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
                val bgColor = if (selected) colors.systemBlue else Color.Black.copy(alpha = 0.4f)
                val textColor = if (selected) Color.White else Color.White.copy(alpha = 0.7f)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(bgColor)
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
        ShutterButton(onClick = onShutter)

        // 右侧占位以保持拍照按钮水平居中
        Spacer(Modifier.width(48.dp))
    }
}

/**
 * 拍照按钮：72dp 圆形，白色边框 + systemBlue 内圈。
 *
 * #3/#36：按下时弹簧缩放反馈（0.85→1.0）+ 快门触感，给予明确的拍摄触发动效。
 */
@Composable
private fun ShutterButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSocialColors.current
    val hapticFeedback = LocalHapticFeedback.current
    // #3：自建 InteractionSource 追踪按压状态，驱动缩放动效
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "shutterScale",
    )
    Box(
        modifier = modifier
            .size(72.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .border(width = 4.dp, color = Color.White, shape = CircleShape)
            .background(Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = {
                    // #3：快门触感反馈
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            ),
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
private fun PermissionRequestCard(
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

/**
 * 执行拍照，将 JPEG 落盘到 cacheDir/capture/<timestamp>.jpg。
 */
internal fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    executor: Executor,
    onResult: (String?) -> Unit,
) {
    if (imageCapture == null) {
        onResult(null)
        return
    }
    val captureDir = File(context.cacheDir, "capture").apply { mkdirs() }
    val file = File(captureDir, "${System.currentTimeMillis()}.jpg")
    val output = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        output,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                Timber.i("拍照落盘 %s", file.absolutePath)
                onResult(file.absolutePath)
            }

            override fun onError(exception: ImageCaptureException) {
                Timber.w(exception, "拍照失败")
                onResult(null)
            }
        },
    )
}

/**
 * 跳转应用设置页（权限设置）。
 */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

/**
 * IMPL-35 / IMPL-36：将 JPEG 中心裁剪为正方形，覆盖原文件。
 * 在拍照回调线程执行，避免阻塞 UI。
 *
 * P1-5：使用 inJustDecodeBounds 探测尺寸 + 动态 inSampleSize，
 * 避免大图全量解码导致 OOM。
 */
private fun cropToSquare(path: String): String? {
    return runCatching {
        // P1-5：先探测原图尺寸
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOpts)
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return null

        // 目标正方形边长 = min(origW, origH)，采样后不低于 1080px
        val targetSize = minOf(origW, origH)
        val sampleTarget = if (targetSize > 1080) 1080 else targetSize
        var sampleSize = 1
        while (minOf(origW, origH) / (sampleSize * 2) >= sampleTarget) {
            sampleSize *= 2
        }

        // 按采样率解码
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val original = BitmapFactory.decodeFile(path, decodeOpts) ?: return null

        val size = minOf(original.width, original.height)
        val x = (original.width - size) / 2
        val y = (original.height - size) / 2
        val cropped = Bitmap.createBitmap(original, x, y, size, size)
        if (cropped !== original) original.recycle()
        FileOutputStream(path).use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        cropped.recycle()
        path
    }.onFailure { Timber.w(it, "正方形裁剪失败 %s", path) }.getOrNull()
}

/**
 * 闪光灯模式映射到 CameraX 常量。
 */
private fun FlashMode.toCameraXFlash(): Int = when (this) {
    FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
    FlashMode.ON -> ImageCapture.FLASH_MODE_ON
    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
}

/**
 * 水平仪判定阈值（度）：|roll| 小于此值视为水平，指示线变绿。
 */
private const val LEVEL_THRESHOLD_DEG = 2f
