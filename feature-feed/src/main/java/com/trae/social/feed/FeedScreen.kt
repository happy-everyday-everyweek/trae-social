package com.trae.social.feed

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.ImageLoader
import com.trae.social.designsystem.components.LoadingShimmer
import com.trae.social.designsystem.components.SocialCard
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialSpacing
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 信息流屏幕。
 *
 * 组合 Paging 3 分页列表 + 下拉刷新 + 上拉加载 + 互动交互。
 * - 首次加载：3 个 shimmer 占位卡片
 * - 空状态：插画 + "暂无推文，去发布第一条吧"
 * - 错误：错误信息 + 重试按钮
 * - 点赞/评论/转发/收藏：乐观更新或弹层
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    modifier: Modifier = Modifier,
    viewModel: FeedViewModel = hiltViewModel(),
    // IMPL-33：向 MainScaffold 派发滚动状态，供 GlassBlurContainer 减半模糊半径
    onScrollingChange: (Boolean) -> Unit = {},
    // IMPL-13：跳过引导时 banner 点击跳转设置页
    onNavigateToSettings: () -> Unit = {},
) {
    val pagingItems = viewModel.feedFlow.collectAsLazyPagingItems()
    val likedIds by viewModel.likedTweetIds.collectAsStateWithLifecycle()
    val bookmarkedIds by viewModel.bookmarkedTweetIds.collectAsStateWithLifecycle()
    val isOnboardingSkipped by viewModel.isOnboardingSkipped.collectAsStateWithLifecycle()

    // 互动弹层状态
    // #212：改用 rememberSaveable，使屏幕旋转或系统回收后已打开的评论弹层/大图查看器/
    // 转发确认弹窗不被关闭。TweetWithAuthor 使用其伴生 Saver，FullScreenImageTarget 使用下方定义的 Saver。
    var commentTarget by rememberSaveable(stateSaver = TweetWithAuthor.Saver) { mutableStateOf<TweetWithAuthor?>(null) }
    var fullScreenTarget by rememberSaveable(stateSaver = FullScreenImageTargetSaver) { mutableStateOf<FullScreenImageTarget?>(null) }
    var retweetTarget by rememberSaveable(stateSaver = TweetWithAuthor.Saver) { mutableStateOf<TweetWithAuthor?>(null) }

    val refreshState = pagingItems.loadState.refresh
    val isInitialLoading = refreshState is LoadState.Loading && pagingItems.itemCount == 0
    val isError = refreshState is LoadState.Error && pagingItems.itemCount == 0
    val isEmpty = refreshState is LoadState.NotLoading && pagingItems.itemCount == 0
    val isRefreshing = refreshState is LoadState.Loading && pagingItems.itemCount > 0
    val isInListMode = !isInitialLoading && !isError && !isEmpty

    // IMPL-33：非列表态（加载/错误/空）时强制清除滚动标记，避免底栏持续半径减半
    LaunchedEffect(isInListMode) {
        if (!isInListMode) onScrollingChange(false)
    }

    // IMPL-13：跳过引导时顶部展示补全配置 banner
    Column(modifier = modifier.fillMaxSize()) {
        if (isOnboardingSkipped) {
            OnboardingSkippedBanner(onNavigateToSettings = onNavigateToSettings)
        }
        // #23：首次加载 shimmer→内容、错误态/空态切换使用 Crossfade 平滑过渡，避免瞬间跳变
        val contentKey = when {
            isInitialLoading -> "loading"
            isError -> "error"
            isEmpty -> "empty"
            else -> "list"
        }
        // Review fix #1：Crossfade 过渡期两态共存，error 分支可能在 refreshState 已变为
        // Loading 时仍被组合，此时 `as LoadState.Error` 会 ClassCastException 崩溃。
        // 在 Crossfade 之前安全提取消息，避免强转。
        val errorMessage = (refreshState as? LoadState.Error)?.error?.message ?: "加载失败"
        Crossfade(
            targetState = contentKey,
            animationSpec = tween(300),
            label = "feed-state",
            modifier = Modifier.fillMaxSize(),
        ) { key ->
            when (key) {
                "loading" -> LoadingPlaceholderList()
                "error" -> ErrorPlaceholder(
                    message = errorMessage,
                    onRetry = {
                        // #135：refresh() 已移除（FeedUiState 死状态），直接重试分页
                        pagingItems.retry()
                    },
                )
                "empty" -> EmptyPlaceholder()
                else -> PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        // #135：refresh() 已移除（FeedUiState 死状态），直接刷新分页数据
                        pagingItems.refresh()
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    FeedList(
                        pagingItems = pagingItems,
                        likedIds = likedIds,
                        bookmarkedIds = bookmarkedIds,
                        imageLoader = viewModel.imageLoader,
                        onImageClick = { uris, index -> fullScreenTarget = FullScreenImageTarget(uris, index) },
                        onLikeClick = { item ->
                            // #101：移除 authorId 参数，内部统一使用 USER_SELF_ID
                            viewModel.likeTweet(item.tweet.id)
                        },
                        onCommentClick = { commentTarget = it },
                        onRetweetClick = { retweetTarget = it },
                        onBookmarkClick = { viewModel.bookmarkTweet(it.tweet.id) },
                        onNotInterestedClick = { viewModel.markNotInterested(it.tweet.id) },
                        onScrollingChange = onScrollingChange,
                    )
                }
            }
        }
    } // Column

    // 评论弹层
    commentTarget?.let { item ->
        CommentSheet(
            tweet = item,
            imageLoader = viewModel.imageLoader,
            onDismiss = { commentTarget = null },
            onSendComment = { text ->
                // #101：移除 authorId 参数，内部统一使用 USER_SELF_ID
                viewModel.commentTweet(item.tweet.id, text)
            },
            loadComments = { viewModel.loadComments(item.tweet.id) },
        )
    }

    // 大图查看器
    fullScreenTarget?.let { target ->
        FullScreenImage(
            imageUris = target.imageUris,
            initialIndex = target.initialIndex,
            imageLoader = viewModel.imageLoader,
            onDismiss = { fullScreenTarget = null },
        )
    }

    // 转发确认弹窗（#37：自定义 on-brand 确认层，替代系统 AlertDialog）
    retweetTarget?.let { item ->
        RetweetConfirmDialog(
            onConfirm = {
                viewModel.retweetTweet(item.tweet)
                retweetTarget = null
            },
            onDismiss = { retweetTarget = null },
        )
    }
}

/**
 * 大图查看器打开目标：该推文全部图片 URI + 被点击图片下标。
 *
 * #212：伴生 [FullScreenImageTargetSaver] 将其展平为 ArrayList<String> + Int，
 * 供 rememberSaveable 在屏幕旋转或系统回收后恢复已打开的大图查看器。
 */
private data class FullScreenImageTarget(
    val imageUris: List<String>,
    val initialIndex: Int,
)

/**
 * #212：FullScreenImageTarget 的 Saver。imageUris 转 ArrayList<String> 以兼容 Bundle 序列化。
 * 改用 mapSaver（key-based），避免字段顺序耦合导致的保存/恢复错位。
 */
private val FullScreenImageTargetSaver: Saver<FullScreenImageTarget, Any> = mapSaver(
    save = {
        mapOf(
            "imageUris" to ArrayList(it.imageUris),
            "initialIndex" to it.initialIndex,
        )
    },
    restore = { map ->
        @Suppress("UNCHECKED_CAST")
        FullScreenImageTarget(
            imageUris = map["imageUris"] as List<String>,
            initialIndex = map["initialIndex"] as Int,
        )
    },
)

/**
 * 信息流列表：LazyColumn + key + 上拉加载 footer。
 */
@Composable
private fun FeedList(
    pagingItems: LazyPagingItems<TweetWithAuthor>,
    likedIds: Set<String>,
    bookmarkedIds: Set<String>,
    imageLoader: ImageLoader,
    onImageClick: (List<String>, Int) -> Unit,
    onLikeClick: (TweetWithAuthor) -> Unit,
    onCommentClick: (TweetWithAuthor) -> Unit,
    onRetweetClick: (TweetWithAuthor) -> Unit,
    onBookmarkClick: (TweetWithAuthor) -> Unit,
    onNotInterestedClick: (TweetWithAuthor) -> Unit,
    onScrollingChange: (Boolean) -> Unit,
) {
    val appendState = pagingItems.loadState.append
    val spacing = LocalSocialSpacing.current
    // IMPL-33：由列表滚动状态派生 isScrolling，回传给 MainScaffold 供 GlassBlurContainer
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { onScrollingChange(it) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = spacing.sm),
    ) {
        items(
            count = pagingItems.itemCount,
            key = pagingItems.itemKey { it.tweet.id },
        ) { index ->
            pagingItems[index]?.let { item ->
                TweetCard(
                    data = item,
                    isLiked = item.tweet.id in likedIds,
                    isBookmarked = item.tweet.id in bookmarkedIds,
                    imageLoader = imageLoader,
                    onImageClick = onImageClick,
                    onLikeClick = { onLikeClick(item) },
                    onCommentClick = { onCommentClick(item) },
                    onRetweetClick = { onRetweetClick(item) },
                    onBookmarkClick = { onBookmarkClick(item) },
                    onNotInterestedClick = { onNotInterestedClick(item) },
                    // #23：列表项进场/位移动画，下拉刷新与新增项有平滑过渡
                    modifier = Modifier.animateItem(),
                )
            }
        }

        // 上拉加载 footer
        when {
            appendState is LoadState.Loading -> {
                item(key = "footer_loading") { LoadingFooter() }
            }
            appendState is LoadState.Error -> {
                item(key = "footer_error") {
                    ErrorFooter(onRetry = { pagingItems.retry() })
                }
            }
            appendState is LoadState.NotLoading && appendState.endOfPaginationReached -> {
                item(key = "footer_end") { EndOfListFooter() }
            }
        }
    }
}

/**
 * 加载占位列表：3 个 shimmer 卡片。
 */
@Composable
private fun LoadingPlaceholderList() {
    val spacing = LocalSocialSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        repeat(3) { ShimmerCard() }
    }
}

@Composable
private fun ShimmerCard() {
    val spacing = LocalSocialSpacing.current
    Row(modifier = Modifier.fillMaxWidth()) {
        LoadingShimmer(
            modifier = Modifier.size(36.dp),
            cornerRadius = 18.dp,
        )
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            LoadingShimmer(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(14.dp),
                cornerRadius = 4.dp,
            )
            LoadingShimmer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp),
                cornerRadius = 4.dp,
            )
            LoadingShimmer(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(14.dp),
                cornerRadius = 4.dp,
            )
            LoadingShimmer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                cornerRadius = 12.dp,
            )
        }
    }
}

/**
 * 转发确认弹层（#37）。
 *
 * 替代系统 AlertDialog，使用 SocialCard + 项目主题色/字体，与应用设计语言一致：
 * 标题 title3、正文 body、确认按钮 systemBlue、背景 secondaryBackground，圈角 16dp。
 */
@Composable
private fun RetweetConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    Dialog(onDismissRequest = onDismiss) {
        SocialCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            elevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(spacing.xl)) {
                Text(
                    text = "确认转发？",
                    style = typography.title3,
                    color = colors.label,
                )
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = "将转发此推文到你的主页",
                    style = typography.body,
                    color = colors.secondaryLabel,
                )
                Spacer(Modifier.height(spacing.xl))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "取消",
                            style = typography.body,
                            color = colors.secondaryLabel,
                        )
                    }
                    Spacer(Modifier.width(spacing.sm))
                    TextButton(onClick = onConfirm) {
                        Text(
                            text = "转发",
                            style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.systemBlue,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 空状态（#31：图标 + 友好文案，与错误态/成功态设计统一）。
 */
@Composable
private fun EmptyPlaceholder() {
    val colors = LocalSocialColors.current
    // #30：统一使用 LocalSocialTypography token
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            modifier = Modifier
                .padding(spacing.xxl)
                // #33：空状态整体合并为一句话义，TalkBack 一次朗读
                .semantics(mergeDescendants = true) {
                    contentDescription = "暂无推文，去发布第一条吧"
                },
        ) {
            // #31：彩色圆 + 图标替代原灰方块 + ":-)"，与 ErrorPlaceholder/DevOptionsScreen 设计统一
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(colors.systemBlue.copy(alpha = 0.12f))
                    .semantics { contentDescription = "" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    tint = colors.systemBlue,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = "暂无推文",
                // #30：bodyLarge → body
                style = typography.body,
                color = colors.label,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "去发布第一条吧",
                // #30：bodyMedium → callout
                style = typography.callout,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 错误状态（#31：图标 + 友好文案，与成功态设计统一）。
 */
@Composable
private fun ErrorPlaceholder(
    message: String,
    onRetry: () -> Unit,
) {
    val colors = LocalSocialColors.current
    // #30：统一使用 LocalSocialTypography token
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    // #31：将技术性 error.message 转译为友好提示
    val friendlyMessage = friendlyErrorMessage(message)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            modifier = Modifier
                .padding(spacing.xxl)
                .semantics(mergeDescendants = true) {
                    contentDescription = friendlyMessage
                },
        ) {
            // 彩色圆 + 错误图标，与 ConnectionTestScreen 成功态设计对应
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(colors.systemRed.copy(alpha = 0.12f))
                    .semantics { contentDescription = "" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = colors.systemRed,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = "加载失败",
                // #30：titleMedium → body
                style = typography.body,
                color = colors.label,
                textAlign = TextAlign.Center,
            )
            Text(
                text = friendlyMessage,
                // #30：bodyMedium → callout
                style = typography.callout,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("重试", modifier = Modifier.padding(start = spacing.xs))
            }
        }
    }
}

/**
 * 将技术性异常文案转译为用户友好的提示（#31）。
 */
private fun friendlyErrorMessage(raw: String): String {
    val lower = raw.lowercase()
    return when {
        lower.contains("unable to resolve host") ||
            lower.contains("unknownhostexception") ||
            lower.contains("timeout") ||
            lower.contains("timed out") -> "网络连接失败，请检查网络后重试"
        lower.contains("unauthorized") || lower.contains("401") -> "身份验证失败，请检查 API Key 配置"
        lower.contains("forbidden") || lower.contains("403") -> "无访问权限，请检查 API 配置"
        lower.contains("not found") || lower.contains("404") -> "请求的资源不存在"
        lower.contains("server") || lower.contains("500") || lower.contains("502") ||
            lower.contains("503") || lower.contains("504") -> "服务器暂时不可用，请稍后重试"
        else -> if (raw.isBlank()) "加载失败，请重试" else raw
    }
}

@Composable
private fun LoadingFooter() {
    val spacing = LocalSocialSpacing.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        LoadingShimmer(
            modifier = Modifier
                .width(120.dp)
                .height(16.dp),
            cornerRadius = 8.dp,
        )
    }
}

@Composable
private fun ErrorFooter(onRetry: () -> Unit) {
    val colors = LocalSocialColors.current
    // #30：统一使用 LocalSocialTypography token
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "加载失败",
                // #30：labelMedium → caption1
                style = typography.caption1,
                color = colors.tertiaryLabel,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun EndOfListFooter() {
    val colors = LocalSocialColors.current
    // #30：统一使用 LocalSocialTypography token
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "已加载全部",
            // #30：labelMedium → caption1
            style = typography.caption1,
            color = colors.tertiaryLabel,
        )
    }
}

/**
 * IMPL-13：跳过引导后的补全配置 banner。
 *
 * 提示用户前往"我的"Tab 配置内容引擎以启用动态生成功能。
 * #17：文案拟人化，"AI 服务"改为"内容引擎"。
 * 点击 banner 跳转设置页。
 */
@Composable
private fun OnboardingSkippedBanner(onNavigateToSettings: () -> Unit) {
    val colors = LocalSocialColors.current
    // #30：统一使用 LocalSocialTypography token
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToSettings() }
            .background(colors.systemBlue.copy(alpha = 0.12f))
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "内容引擎未配置，部分动态暂不可用",
            // #30：bodySmall → subheadline
            style = typography.subheadline,
            color = colors.systemBlue,
        )
        Text(
            text = "前往设置 >",
            // #30：labelLarge → subheadline
            style = typography.subheadline,
            color = colors.systemBlue,
        )
    }
}
