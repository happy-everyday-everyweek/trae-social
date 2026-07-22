package com.trae.social.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trae.social.core.data.entity.AccountEntity
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.trae.social.designsystem.components.LoadingShimmer
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialSpacing
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 时间线（我的相册）页面：朋友圈式布局。
 *
 * 数据来源：[TimelineViewModel.timelineFlow]，仅含带图推文，按日期分组展示。
 *
 * @param onPublishClick 空状态下"去发布第一条带图推文"按钮回调，由外层传入用于导航到发布页
 */
@Composable
fun TimelineScreen(
    modifier: Modifier = Modifier,
    onPublishClick: () -> Unit = {},
    // IMPL-33：向 MainScaffold 派发滚动状态，供 GlassBlurContainer 减半模糊半径
    onScrollingChange: (Boolean) -> Unit = {},
) {
    val viewModel: TimelineViewModel = hiltViewModel()
    val state by viewModel.timelineFlow.collectAsStateWithLifecycle()
    // #13：当前用户资料，用于时间线头部真实头像/昵称
    val selfProfile by viewModel.selfProfile.collectAsStateWithLifecycle()

    // 大图浏览器目标：当前点击的分组 + 起始下标
    var viewerTarget by remember { mutableStateOf<ViewerTarget?>(null) }

    val isSuccess = state is TimelineUiState.Success
    // IMPL-33：非成功态强制清除滚动标记，避免底栏持续半径减半
    LaunchedEffect(isSuccess) {
        if (!isSuccess) onScrollingChange(false)
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val current = state) {
            is TimelineUiState.Loading -> TimelineLoading()
            is TimelineUiState.Empty -> TimelineEmpty(onPublishClick = onPublishClick)
            is TimelineUiState.Error -> TimelineError(
                message = current.message,
                // #162：错误态提供重试入口，与 FeedScreen.ErrorPlaceholder 设计一致
                onRetry = { viewModel.retry() },
            )
            is TimelineUiState.Success -> TimelineContent(
                groups = current.groups,
                selfProfile = selfProfile,
                // M3 修复：复用 ViewModel 注入的共享 @SvgImageLoader 单例，
                // 替代 Composable 内 rememberSvgImageLoader() 本地构造。
                imageLoader = viewModel.imageLoader,
                onImageClick = { group, index ->
                    viewerTarget = ViewerTarget(
                        items = group.items,
                        initialIndex = index,
                        dateLabel = group.dateLabel,
                    )
                },
                onScrollingChange = onScrollingChange,
            )
        }
    }

    viewerTarget?.let { target ->
        FullScreenImageViewer(
            items = target.items,
            initialIndex = target.initialIndex,
            dateLabel = target.dateLabel,
            // M3 修复：大图浏览器同样复用共享 ImageLoader
            imageLoader = viewModel.imageLoader,
            onDismiss = { viewerTarget = null },
        )
    }
}

/**
 * 大图浏览器打开目标。
 */
private data class ViewerTarget(
    val items: List<TimelineItem>,
    val initialIndex: Int,
    val dateLabel: String,
)

/**
 * 成功态内容：顶部信息 + 按日期分组的图片网格。
 */
@Composable
private fun TimelineContent(
    groups: List<TimelineGroup>,
    selfProfile: AccountEntity?,
    imageLoader: ImageLoader,
    onImageClick: (TimelineGroup, Int) -> Unit,
    onScrollingChange: (Boolean) -> Unit,
) {
    val colors = LocalSocialColors.current
    // IMPL-33：由列表滚动状态派生 isScrolling，回传给 MainScaffold 供 GlassBlurContainer
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { onScrollingChange(it) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.systemBackground),
    ) {
        item(key = "header") { TimelineHeader(account = selfProfile, imageLoader = imageLoader) }
        items(items = groups, key = { group -> group.date.toString() }) { group ->
            GroupBlock(group = group, imageLoader = imageLoader, onImageClick = onImageClick)
            SocialDivider()
        }
    }
}

/**
 * 顶部：用户头像（48dp）+ 昵称 + "我的相册"标题。
 *
 * #13：复用个人主页的 user-self 账号资料（avatarSeed / displayName），
 * 替代原先硬编码的蓝色"我"占位，保证两处身份呈现一致。
 */
@Composable
private fun TimelineHeader(account: AccountEntity?, imageLoader: ImageLoader) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    // 显示名回退"我"（与 ProfileScreen 一致：空名时显示"我"）
    val displayName = account?.displayName?.ifBlank { null } ?: "我"
    val spacing = LocalSocialSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimelineAvatar(
            avatarSeed = account?.avatarSeed,
            imageLoader = imageLoader,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.size(spacing.md))
        Column {
            Text(
                text = "我的相册",
                style = typography.headline,
                fontWeight = FontWeight.Bold,
                color = colors.label,
            )
            Text(
                text = displayName,
                style = typography.subheadline,
                color = colors.secondaryLabel,
            )
        }
    }
}

/**
 * 时间线头部头像：优先加载 user-self 的 SVG 头像，加载中/失败/无 seed 时回退占位。
 *
 * M3 修复：复用共享 [@SvgImageLoader] [ImageLoader]（由 [TimelineViewModel] 注入），
 * 替代本模块 [rememberSvgImageLoader] 本地构造。头像 URI 由 [avatarUriFromSeed] 派生。
 */
@Composable
private fun TimelineAvatar(
    avatarSeed: String?,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (avatarSeed.isNullOrBlank()) {
        MonogramAvatar(modifier = modifier)
        return
    }
    val request = remember(avatarSeed, context) {
        ImageRequest.Builder(context)
            .data(avatarUriFromSeed(avatarSeed))
            .crossfade(true)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = "头像",
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(CircleShape),
        loading = { MonogramAvatar(Modifier.fillMaxSize()) },
        error = { MonogramAvatar(Modifier.fillMaxSize()) },
    )
}

/**
 * 默认头像占位：systemBlue 圆形 + "我"字。
 *
 * 用于 user-self 账号尚未加载或头像加载失败时回退。
 */
@Composable
private fun MonogramAvatar(modifier: Modifier = Modifier) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.systemBlue),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "我",
            color = Color.White,
            style = typography.body,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 单日分组块：日期标题 + 图片网格。
 */
@Composable
private fun GroupBlock(
    group: TimelineGroup,
    imageLoader: ImageLoader,
    onImageClick: (TimelineGroup, Int) -> Unit,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        Text(
            text = group.dateLabel,
            style = typography.subheadline,
            fontWeight = FontWeight.SemiBold,
            color = colors.label,
        )
        Spacer(modifier = Modifier.size(spacing.sm))
        MediaGrid(group = group, imageLoader = imageLoader, onImageClick = onImageClick)
    }
}

/**
 * 图片网格：根据单日图片数量差异化布局。
 * - 1 张：大图
 * - 2 张：并排两张
 * - 3 张：1 大 + 2 小
 * - 4+ 张：3 列网格，最多展示 9 张，第 9 张显示 "+N" 角标
 */
@Composable
private fun MediaGrid(
    group: TimelineGroup,
    imageLoader: ImageLoader,
    onImageClick: (TimelineGroup, Int) -> Unit,
) {
    val items = group.items
    when (items.size) {
        0 -> Unit
        1 -> SingleImageLayout(item = items[0], imageLoader = imageLoader, onClick = { onImageClick(group, 0) })
        2 -> TwoImageLayout(
            items = items,
            imageLoader = imageLoader,
            onClick = { index -> onImageClick(group, index) },
        )
        3 -> ThreeImageLayout(
            items = items,
            imageLoader = imageLoader,
            onClick = { index -> onImageClick(group, index) },
        )
        else -> GridImageLayout(
            items = items,
            imageLoader = imageLoader,
            onClick = { index -> onImageClick(group, index) },
        )
    }
}

/**
 * 单图：fillMaxWidth，最大高度 320dp，圆角 12dp + 下方时间与摘要。
 */
@Composable
private fun SingleImageLayout(item: TimelineItem, imageLoader: ImageLoader, onClick: () -> Unit) {
    Column {
        TimelineImageCell(
            item = item,
            imageLoader = imageLoader,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
            onClick = onClick,
        )
        ImageCaption(item = item)
    }
}

/**
 * 两图：并排，各 fillMaxWidth/2，高度 160dp + 各自下方时间与摘要。
 */
@Composable
private fun TwoImageLayout(items: List<TimelineItem>, imageLoader: ImageLoader, onClick: (Int) -> Unit) {
    // #32：网格间距 2dp→4dp，与全项目间距节奏一致
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
            Column(modifier = Modifier.weight(1f)) {
                TimelineImageCell(
                    item = item,
                    imageLoader = imageLoader,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                    onClick = { onClick(index) },
                )
                ImageCaption(item = item)
            }
        }
    }
}

/**
 * 三图：1 大图（fillMaxWidth 高 200dp）+ 下方两小图（各 fillMaxWidth/2 高 100dp）。
 */
@Composable
private fun ThreeImageLayout(items: List<TimelineItem>, imageLoader: ImageLoader, onClick: (Int) -> Unit) {
    val big = items[0]
    val smalls = items.drop(1)
    val spacing = LocalSocialSpacing.current
    Column {
        TimelineImageCell(
            item = big,
            imageLoader = imageLoader,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
            onClick = { onClick(0) },
        )
        ImageCaption(item = big)
        Spacer(modifier = Modifier.size(spacing.xs))
        // #32：网格间距 2dp→4dp
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            smalls.forEachIndexed { index, item ->
                TimelineImageCell(
                    item = item,
                    imageLoader = imageLoader,
                    modifier = Modifier
                        .weight(1f)
                        .height(100.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    onClick = { onClick(index + 1) },
                )
            }
        }
    }
}

/**
 * 4+ 张：3 列网格，每格 1:1，间距 2dp。
 * 最多展示 9 格；超过 9 张时，第 9 格显示 "+N" 角标（N = 总数 - 9）。
 */
@Composable
private fun GridImageLayout(items: List<TimelineItem>, imageLoader: ImageLoader, onClick: (Int) -> Unit) {
    val totalCount = items.size
    val displayCount = minOf(totalCount, GRID_MAX_DISPLAY)
    val rows = (0 until displayCount).chunked(GRID_COLUMNS)
    val typography = LocalSocialTypography.current
    // #32：网格间距 2dp→4dp，与全项目间距节奏一致
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { rowIndices ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowIndices.forEach { index ->
                    val isOverflowCell = index == GRID_MAX_DISPLAY - 1 && totalCount > GRID_MAX_DISPLAY
                    val overlay = if (isOverflowCell) "+${totalCount - GRID_MAX_DISPLAY}" else null
                    Box(modifier = Modifier.weight(1f)) {
                        TimelineImageCell(
                            item = items[index],
                            imageLoader = imageLoader,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                // #32：网格单元圆角 4dp→8dp，对齐 SocialShapes.small
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                            onClick = { onClick(index) },
                        )
                        if (overlay != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = overlay,
                                    color = Color.White,
                                    style = typography.headline,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
                // #194：末行不足 3 列时补透明占位，避免 weight(1f) 拉伸末行 cell
                // 破坏 3 列等宽网格。占位与正常 cell 同 weight，使末行 cell 宽度与上方各行一致。
                val shortfall = GRID_COLUMNS - rowIndices.size
                if (shortfall > 0) {
                    repeat(shortfall) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * 单张图片单元格：Coil 加载（含 SVG），加载/失败态显示 shimmer 占位。
 *
 * M3 修复：[imageLoader] 由上层传入（共享 [@SvgImageLoader] 单例），
 * 替代原 Composable 内 [rememberSvgImageLoader] 本地构造。
 */
@Composable
private fun TimelineImageCell(
    item: TimelineItem,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val request = remember(item.mediaPath, context) {
        ImageRequest.Builder(context)
            .data(mediaPathToCoilUrl(item.mediaPath))
            .crossfade(true)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = item.text,
        imageLoader = imageLoader,
        contentScale = contentScale,
        modifier = modifier.clickable(onClick = onClick),
        loading = {
            LoadingShimmer(modifier = Modifier.fillMaxSize(), cornerRadius = 12.dp)
        },
        error = {
            LoadingShimmer(modifier = Modifier.fillMaxSize(), cornerRadius = 12.dp)
        },
    )
}

/**
 * 图片下方说明：发布时间（HH:mm）+ 文本摘要（1 行，省略号）。
 */
@Composable
private fun ImageCaption(item: TimelineItem) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    Column(modifier = Modifier.padding(top = spacing.xs, bottom = spacing.xs)) {
        Text(
            text = item.timeLabel,
            style = typography.caption2,
            color = colors.tertiaryLabel,
        )
        if (item.text.isNotBlank()) {
            Text(
                text = item.text,
                style = typography.subheadline,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 加载态：shimmer 占位列表。
 */
@Composable
private fun TimelineLoading() {
    val colors = LocalSocialColors.current
    val spacing = LocalSocialSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.systemBackground)
            .padding(spacing.lg),
    ) {
        repeat(3) {
            LoadingShimmer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                cornerRadius = 6.dp,
            )
            Spacer(modifier = Modifier.size(spacing.md))
            LoadingShimmer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                cornerRadius = 12.dp,
            )
            Spacer(modifier = Modifier.size(spacing.xl))
        }
    }
}

/**
 * 空状态：几何插画 + "去发布第一条带图推文"按钮。
 */
@Composable
private fun TimelineEmpty(onPublishClick: () -> Unit) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.systemBackground)
            .padding(spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyIllustration(modifier = Modifier.size(160.dp))
        Spacer(modifier = Modifier.size(spacing.xl))
        Text(
            text = "还没有带图推文",
            style = typography.body,
            color = colors.label,
        )
        Spacer(modifier = Modifier.size(spacing.sm))
        Text(
            text = "发布第一条带图推文，开始记录你的相册",
            style = typography.subheadline,
            color = colors.secondaryLabel,
        )
        Spacer(modifier = Modifier.size(spacing.xl))
        Button(onClick = onPublishClick) {
            Text(text = "去发布第一条带图推文")
        }
    }
}

/**
 * 空状态几何插画：圆 / 矩形 / 三角形组合，呼应图库生成器的几何风格。
 */
@Composable
private fun EmptyIllustration(modifier: Modifier = Modifier) {
    val colors = LocalSocialColors.current
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val canvasSize = size.minDimension
        val ringColor = colors.systemBlue.copy(alpha = 0.85f)
        val rectColor = colors.systemPurple.copy(alpha = 0.6f)
        val triColor = colors.systemOrange.copy(alpha = 0.7f)

        // 圆
        drawCircle(
            color = ringColor,
            radius = canvasSize * 0.28f,
            center = Offset(canvasSize * 0.38f, canvasSize * 0.40f),
            style = Stroke(width = canvasSize * 0.04f),
        )
        // 实心小圆
        drawCircle(
            color = colors.systemGreen.copy(alpha = 0.8f),
            radius = canvasSize * 0.10f,
            center = Offset(canvasSize * 0.70f, canvasSize * 0.30f),
        )
        // 矩形
        drawRect(
            color = rectColor,
            topLeft = Offset(canvasSize * 0.58f, canvasSize * 0.55f),
            size = Size(canvasSize * 0.28f, canvasSize * 0.28f),
        )
        // 三角形
        val path = Path().apply {
            moveTo(canvasSize * 0.22f, canvasSize * 0.78f)
            lineTo(canvasSize * 0.45f, canvasSize * 0.78f)
            lineTo(canvasSize * 0.335f, canvasSize * 0.55f)
            close()
        }
        drawPath(path = path, color = triColor)
    }
}

/**
 * 错误态。
 *
 * #162：与 FeedScreen.ErrorPlaceholder 设计统一——错误图标 + 标题 + 描述 + 重试按钮，
 * 支持就地重试，避免用户必须退出再进入才能恢复。
 */
@Composable
private fun TimelineError(message: String, onRetry: () -> Unit) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.systemBackground)
            .padding(spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 彩色圆 + 错误图标，与 FeedScreen.ErrorPlaceholder 设计对应
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colors.systemRed.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                tint = colors.systemRed,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(modifier = Modifier.size(spacing.md))
        Text(
            text = "加载失败",
            style = typography.body,
            color = colors.label,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.size(spacing.sm))
        Text(
            text = message,
            style = typography.subheadline,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.size(spacing.md))
        // #162：重试按钮，与 FeedScreen 错误态一致
        OutlinedButton(onClick = onRetry) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(text = "重试", modifier = Modifier.padding(start = spacing.xs))
        }
    }
}

/**
 * 将 TweetEntity.mediaPath（asset 相对路径）转换为 Coil 可加载的地址。
 * 已是完整协议头（http/https/file/content）时原样返回，否则补 "file:///android_asset/" 前缀。
 * IMPL-39：mediaPath 可能是逗号分隔的多图列表，取第一张显示。
 * #187：与 FeedUtils/ProfileUtils.toSingleImageUri 对齐——补 "file://<path>" 分支处理绝对路径，
 * 否则相机/相册发布的 "/sdcard/xxx" 会被拼成 "file:///android_asset//sdcard/xxx" 导致 Coil 加载失败。
 */
internal fun mediaPathToCoilUrl(path: String): String {
    if (path.isBlank()) return path
    // 多图取第一张
    val firstPath = path.substringBefore(",").trim()
    if (firstPath.isBlank()) return firstPath
    val hasScheme = firstPath.startsWith("http://") ||
        firstPath.startsWith("https://") ||
        firstPath.startsWith("file://") ||
        firstPath.startsWith("content://")
    return when {
        hasScheme -> firstPath
        // #187：绝对路径（以 "/" 开头）补 "file://" 前缀，与 Feed/Profile 行为一致
        firstPath.startsWith("/") -> "file://$firstPath"
        else -> "file:///android_asset/$firstPath"
    }
}

/**
 * 由 avatarSeed 派生确定性头像 asset URI（与 ProfileUtils.avatarUriFromSeed / FeedUtils.avatarUriFromSeed 等价）。
 *
 * #13：feature 模块间不相互依赖工具函数，按项目既有约定在本模块内复制一份。
 *
 * #84 / #187：与 FeedUtils / ProfileUtils 对齐——在 200 张图的全池上取单一模
 * （flatIndex % 200）再映射到 (类别, 桶内下标)，并对 seedHash 做 [mixHash] 雪崩混合
 * 散布 String.hashCode 的位聚集，使相邻 seed 映射到远端桶，降低碰撞概率。
 * 替代原 `category % 8 + index % 25` 双模组合，与 Feed/Profile 三处对齐，确保同一
 * avatarSeed 在 Feed/Profile/Timeline 三处产出相同头像 URI。
 *
 * gallery 资源为 8 类别 × 25 张 = 200 张唯一图片（见
 * app/src/main/assets/gallery/index.json）。账号总数 221 > 200，鸽巢原理保证至少
 * 21 个账号必然与其他账号共享头像——此为资源数量上限决定，无法在纯函数内消除。
 */
internal fun avatarUriFromSeed(avatarSeed: String): String {
    val flatIndex = (mixHash(avatarSeed.hashCode()) and 0x7FFFFFFF) % TOTAL_AVATAR_IMAGES
    val category = avatarCategories[flatIndex / IMAGES_PER_CATEGORY]
    val index = (flatIndex % IMAGES_PER_CATEGORY) + 1
    return "file:///android_asset/gallery/$category/$index.svg"
}

/**
 * MurmurHash3 32-bit finalizer（雪崩混合），与 FeedUtils.mixHash / ProfileUtils.mixHash 对齐，
 * 用于打散 String.hashCode 的位聚集，使输入的微小变化能均匀传播到所有输出位，降低取模后的聚集碰撞。
 */
private fun mixHash(h: Int): Int {
    var x = h
    x = x xor (x ushr 16)
    x = x * (0x85EBCA6B.toInt())
    x = x xor (x ushr 13)
    x = x * (0xC2B2AE35.toInt())
    x = x xor (x ushr 16)
    return x
}

// #84：与 FeedUtils / ProfileUtils 对齐的 8 类别 × 25 张 = 200 张头像池常量
private val avatarCategories = listOf(
    "landscape", "city", "food", "nature",
    "pet", "sport", "tech", "art"
)
private const val IMAGES_PER_CATEGORY = 25
private const val TOTAL_AVATAR_IMAGES = 200 // avatarCategories.size * IMAGES_PER_CATEGORY

private const val GRID_COLUMNS = 3
private const val GRID_MAX_DISPLAY = 9
