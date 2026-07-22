package com.trae.social.publish

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.components.ActionButton
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 编辑器模式内容（SubTask 14.6）。
 *
 * 相册选图 + 裁剪（自实现简化版可拖拽矩形） + 滤镜（5 个 ColorMatrix 预设）。
 *
 * IMPL-36：原实现裁剪区域写死 70%、拖拽 48px 才到边沿且坐标系为硬编码近似；
 * `decodeBitmap` 固定 `inSampleSize=2` 对 4000×6000 大图仍解码到 2000×3000≈24MB，
 * 滤镜切换累积分配合计上百 MB，低端机 OOM；Bitmap 不 recycle。
 * 现改为：(1) 用 `onSizeChanged` 取容器真实尺寸，按比例精确映射裁剪框到源图坐标；
 * (2) `decodeBitmap` 先 `inJustDecodeBounds=true` 探测尺寸，动态算 inSampleSize
 * 使最长边降到 [MAX_DECODE_EDGE] 以下；(3) 缩略图降到 32×32；(4) 中间 Bitmap 用完 recycle。
 *
 * #9：新增配文输入，用户可在编辑器内直接输入配文，与发布流程共享同一 caption 状态。
 *
 * @param onEditComplete 编辑完成回调（传入落盘文件绝对路径）
 * @param caption 当前配文文本
 * @param onCaptionChange 配文变更回调
 */
@Composable
fun EditorModeContent(
    onEditComplete: (String) -> Unit,
    caption: String,
    onCaptionChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    // #175：解码移到 IO 线程，需要 coroutine scope
    val scope = rememberCoroutineScope()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // review 修复：追踪当前解码协程，快速重选时取消前一次未完成的解码，避免 bitmap 泄漏
    var decodeJob: Job? by remember { mutableStateOf(null) }
    var selectedFilter by remember { mutableStateOf(FilterPreset.ORIGINAL) }
    // 裁剪框以容器尺寸的比例表示（left/top/right/bottom ∈ [0,1]），默认居中 70%
    var cropRect by remember { mutableStateOf(Rect(0.15f, 0.15f, 0.85f, 0.85f)) }
    // 当前裁剪比例（null = 自由）；切换图片时重置
    var aspectRatio by remember { mutableStateOf<Float?>(null) }
    // 容器真实像素尺寸，供裁剪坐标精确映射到源图
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // 选图/换图时重置裁剪框与比例
    fun resetCrop() {
        cropRect = Rect(0.15f, 0.15f, 0.85f, 0.85f)
        aspectRatio = null
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        pickedUri = uri
        if (uri != null) {
            // #175：decodeBitmap 移到 Dispatchers.IO，避免主线程 I/O + 解码导致 ANR。
            // 不在此处回收旧 sourceBitmap：ORIGINAL 滤镜下 displayBitmap === sourceBitmap，
            // Image 仍显示旧 Bitmap 时回收会导致渲染崩溃。旧 sourceBitmap 由 CropOverlay 的
            // LaunchedEffect（ORIGINAL 时 old displayBitmap === oldSource，切换时回收）处理，
            // 或在组合离开时由 DisposableEffect 回收。
            //
            // review 修复：取消前一次未完成的解码任务。快速重选 A→B 时，A 的解码协程被取消，
            // 避免 A 的 bitmap 解码完成后覆盖 B 的结果或成为孤儿（~16MB ARGB_8888 仅靠 GC
            // finalizer 回收，低内存设备可能 OOM）。解码完成后若协程已被取消，回收刚解码的
            // bitmap 避免 leak。
            decodeJob?.cancel()
            decodeJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { decodeBitmap(context, uri) }.getOrNull()
                }
                try {
                    ensureActive()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 解码期间用户重选了新图，回收刚解码的 bitmap 避免泄漏
                    bitmap?.recycle()
                    throw e
                }
                sourceBitmap = bitmap
                resetCrop()
            }
        }
    }

    // #175：组合离开时回收 sourceBitmap，避免内存泄漏
    DisposableEffect(Unit) {
        onDispose {
            sourceBitmap?.recycle()
        }
    }

    // 首次进入自动打开相册
    LaunchedEffect(Unit) {
        if (pickedUri == null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.systemBackground)) {
        val bitmap = sourceBitmap
        if (pickedUri == null || bitmap == null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "从相册选择一张图片",
                    style = typography.headline,
                    color = colors.label,
                )
                ActionButton(
                    text = "选择图片",
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // 预览 + 可拖拽裁剪框（边角可调大小）
            CropOverlay(
                bitmap = bitmap,
                filter = selectedFilter,
                cropRect = cropRect,
                onCropRectChange = { cropRect = it },
                containerSize = containerSize,
                onContainerSizeChange = { containerSize = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
            )

            // 裁剪比例预设条（自由 / 1:1 / 4:3 / 16:9）
            AspectRatioRow(
                aspectRatio = aspectRatio,
                onSelect = { ratio ->
                    aspectRatio = ratio
                    // 切换比例时按容器与源图比例重新居中计算裁剪框
                    if (containerSize != IntSize.Zero && bitmap != null) {
                        cropRect = computeCenteredCropRect(ratio, containerSize, bitmap)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // 滤镜选择条
            FilterRow(
                selected = selectedFilter,
                onSelect = { selectedFilter = it },
                sourceBitmap = bitmap,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )

            // #9：编辑器内配文输入，与发布流程共享同一 caption 状态
            CaptionInput(
                text = caption,
                onTextChanged = onCaptionChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // 底部操作
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    text = "重新选择",
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = "确认裁剪",
                    onClick = {
                        val edited = applyCropAndFilter(bitmap, cropRect, containerSize, selectedFilter)
                        val path = saveBitmap(context, edited)
                        // IMPL-36：裁剪结果落盘后立即释放，避免叠加原图占用双倍内存
                        if (edited !== bitmap) edited.recycle()
                        if (path != null) onEditComplete(path)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
