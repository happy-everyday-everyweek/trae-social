package com.trae.social.publish

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import timber.log.Timber
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    // #36：拍照闪光动画（白色遮罩淡出）
    val captureFlashAlpha = remember { Animatable(0f) }
    // #36：拍照处理中状态（显示加载指示器）
    var isCapturing by remember { mutableStateOf(false) }

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
        // #36：防止重复触发，拍照处理中时忽略再次点击
        if (!isCapturing) {
            isCapturing = true
            // #36：拍照闪光动画——白色遮罩快速淡出
            scope.launch {
                captureFlashAlpha.snapTo(CAPTURE_FLASH_ALPHA)
                captureFlashAlpha.animateTo(0f, tween(CAPTURE_FLASH_DURATION_MS))
            }
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
                isCapturing = false
            }
        }
    }
    // #36：长按连拍——快速连续拍照多张
    // M5 修复：连拍期间置 isCapturing=true 防止并发触发；逐张等待 takePicture 回调完成后再拍下一张
    val onBurstShutter: () -> Unit = {
        if (!isCapturing) {
            isCapturing = true
            scope.launch {
                repeat(BURST_COUNT) {
                    // #36：每次连拍触发闪光动画（m8 修复：用更短的持续时间避免 > 间隔导致卡顿）
                    launch {
                        captureFlashAlpha.snapTo(CAPTURE_FLASH_ALPHA)
                        captureFlashAlpha.animateTo(0f, tween(BURST_FLASH_DURATION_MS))
                    }
                    // M5 修复：等待上一张 takePicture 回调完成后再拍下一张，
                    // 避免同一 ImageCapture 上并发 takePicture 抛 IllegalStateException
                    val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
                    capturePhoto(context, imageCapture, executor) { path ->
                        deferred.complete(path)
                    }
                    val path = deferred.await()
                    if (path != null) {
                        val finalPath = if (ratio == CaptureRatio.SQUARE) {
                            cropToSquare(path) ?: path
                        } else {
                            path
                        }
                        onCapture(finalPath)
                    }
                    delay(BURST_INTERVAL_MS)
                }
                isCapturing = false
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
                // #188：独立加 statusBarsPadding，不依赖父级 inset 处理
                .statusBarsPadding()
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
            // #36：传入连拍回调
            onBurstShutter = onBurstShutter,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )

        // #36：拍照闪光白色遮罩（快速淡出，模拟快门闪光，提供拍照视觉反馈）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = captureFlashAlpha.value)),
        )

        // #36：拍照处理中加载指示器，提示用户正在处理
        if (isCapturing) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

// #36：拍照闪光动画参数
/** 闪光遮罩初始透明度 */
private const val CAPTURE_FLASH_ALPHA = 0.8f
/** 闪光淡出时长（毫秒） */
private const val CAPTURE_FLASH_DURATION_MS = 200

// #36：连拍参数
/** 长按连拍张数 */
private const val BURST_COUNT = 5
/** 连拍间隔（毫秒） */
private const val BURST_INTERVAL_MS = 150L
// m8 修复：连拍闪光动画时长（毫秒）。短于连拍间隔 150ms，
// 避免上一次 animateTo 未结束时被下一次 snapTo 重置导致动画卡顿。
private const val BURST_FLASH_DURATION_MS = 100
