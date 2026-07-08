package com.trae.social.feed

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.ImageLoader
import com.trae.social.designsystem.components.LoadingShimmer
import com.trae.social.designsystem.theme.LocalSocialColors

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
) {
    val pagingItems = viewModel.feedFlow.collectAsLazyPagingItems()
    val likedIds by viewModel.likedTweetIds.collectAsStateWithLifecycle()
    val bookmarkedIds by viewModel.bookmarkedTweetIds.collectAsStateWithLifecycle()
    val isOnboardingSkipped by viewModel.isOnboardingSkipped.collectAsStateWithLifecycle()

    // 互动弹层状态
    var commentTarget by remember { mutableStateOf<TweetWithAuthor?>(null) }
    var fullScreenImageUri by remember { mutableStateOf<String?>(null) }
    var retweetTarget by remember { mutableStateOf<TweetWithAuthor?>(null) }

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
            OnboardingSkippedBanner()
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                viewModel.refresh()
                pagingItems.refresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
        when {
            isInitialLoading -> LoadingPlaceholderList()
            isError -> ErrorPlaceholder(
                message = (refreshState as LoadState.Error).error.message ?: "加载失败",
                onRetry = {
                    viewModel.refresh()
                    pagingItems.retry()
                },
            )
            isEmpty -> EmptyPlaceholder()
            else -> FeedList(
                pagingItems = pagingItems,
                likedIds = likedIds,
                bookmarkedIds = bookmarkedIds,
                imageLoader = viewModel.imageLoader,
                onImageClick = { fullScreenImageUri = it },
                onLikeClick = { item ->
                    viewModel.likeTweet(item.tweet.id, item.tweet.authorId)
                },
                onCommentClick = { commentTarget = it },
                onRetweetClick = { retweetTarget = it },
                onBookmarkClick = { viewModel.bookmarkTweet(it.tweet.id) },
                onScrollingChange = onScrollingChange,
            )
        }
        } // PullToRefreshBox
    } // Column

    // 评论弹层
    commentTarget?.let { item ->
        CommentSheet(
            tweet = item,
            imageLoader = viewModel.imageLoader,
            onDismiss = { commentTarget = null },
            onSendComment = { text ->
                viewModel.commentTweet(item.tweet.id, item.tweet.authorId, text)
            },
        )
    }

    // 大图查看器
    fullScreenImageUri?.let { uri ->
        FullScreenImage(
            imageUri = uri,
            imageLoader = viewModel.imageLoader,
            onDismiss = { fullScreenImageUri = null },
        )
    }

    // 转发确认弹窗
    retweetTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { retweetTarget = null },
            title = { Text("确认转发？") },
            text = { Text("将转发此推文到你的主页") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.retweetTweet(item.tweet)
                    retweetTarget = null
                }) { Text("转发") }
            },
            dismissButton = {
                TextButton(onClick = { retweetTarget = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 信息流列表：LazyColumn + key + 上拉加载 footer。
 */
@Composable
private fun FeedList(
    pagingItems: LazyPagingItems<TweetWithAuthor>,
    likedIds: Set<String>,
    bookmarkedIds: Set<String>,
    imageLoader: ImageLoader,
    onImageClick: (String) -> Unit,
    onLikeClick: (TweetWithAuthor) -> Unit,
    onCommentClick: (TweetWithAuthor) -> Unit,
    onRetweetClick: (TweetWithAuthor) -> Unit,
    onBookmarkClick: (TweetWithAuthor) -> Unit,
    onScrollingChange: (Boolean) -> Unit,
) {
    val appendState = pagingItems.loadState.append
    // IMPL-33：由列表滚动状态派生 isScrolling，回传给 MainScaffold 供 GlassBlurContainer
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { onScrollingChange(it) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(3) { ShimmerCard() }
    }
}

@Composable
private fun ShimmerCard() {
    Row(modifier = Modifier.fillMaxWidth()) {
        LoadingShimmer(
            modifier = Modifier.size(36.dp),
            cornerRadius = 18.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
 * 空状态。
 */
@Composable
private fun EmptyPlaceholder() {
    val colors = LocalSocialColors.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            // 简易空状态插画：灰色圆角方块占位
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.tertiaryBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ":-)",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.tertiaryLabel,
                )
            }
            Text(
                text = "暂无推文，去发布第一条吧",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 错误状态。
 */
@Composable
private fun ErrorPlaceholder(
    message: String,
    onRetry: () -> Unit,
) {
    val colors = LocalSocialColors.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("重试", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun LoadingFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "加载失败",
                style = MaterialTheme.typography.labelMedium,
                color = colors.tertiaryLabel,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun EndOfListFooter() {
    val colors = LocalSocialColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "已加载全部",
            style = MaterialTheme.typography.labelMedium,
            color = colors.tertiaryLabel,
        )
    }
}

/**
 * IMPL-13：跳过引导后的补全配置 banner。
 *
 * 提示用户前往"我的"Tab 配置 API Key 以启用 AI 功能。
 */
@Composable
private fun OnboardingSkippedBanner() {
    val colors = LocalSocialColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.systemBlue.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "AI 服务未配置，部分功能不可用",
            style = MaterialTheme.typography.bodySmall,
            color = colors.systemBlue,
        )
        Text(
            text = "前往设置",
            style = MaterialTheme.typography.labelLarge,
            color = colors.systemBlue,
        )
    }
}
