package com.trae.social.profile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.designsystem.components.CapsuleTab
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.components.socialClickable
import com.trae.social.designsystem.theme.LocalSocialSpacing
import com.trae.social.designsystem.theme.LocalSocialTypography
import com.trae.social.designsystem.theme.minTouchTarget
import com.trae.social.designsystem.theme.socialColors

/**
 * 全屏大图查看器状态（#8）：图片 URI 列表 + 初始下标。
 *
 * #231：标注 @Immutable。`images` 为 `List<String>`，会被 Compose 推断为 Unstable
 * 导致 FullScreenImageState 不可 skip。该类作为 mutableStateOf 值构造后即不再变，
 * 可安全标注 @Immutable。
 */
@Immutable
private data class FullScreenImageState(
    val images: List<String>,
    val index: Int,
)

/**
 * 个人主页（IMPL-2：替换占位实现）。
 *
 * 顶部设置入口；资料卡（头像/昵称/简介/统计）；推文/媒体/喜欢 Tab。
 *
 * @param onNavigateToSettings 点击设置入口
 * @param onNavigateToFollowList 点击关注/粉丝统计，参数为列表类型
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToFollowList: (FollowListType) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val colors = socialColors()
    // #8：全屏大图查看器状态（媒体网格与推文图片共用）
    var fullScreenState by remember { mutableStateOf<FullScreenImageState?>(null) }
    // #233：onImageClick 用 remember 缓存，避免每次 ProfileScreen 重组（selectedTab
    // 切换、fullScreenState 变化、uiState 变化）都 new 一个新 lambda 实例向下传
    // 破坏子组件 TweetsTab/MediaTab/LikesTab skip。fullScreenState 是 by 委托的
    // State，闭包内写入会路由到原 State，无需把 fullScreenState 加进 key。
    // lambda 仅捕获稳定的 MutableState 委托引用（fullScreenState），故无需 key
    val onImageClick: (List<String>, Int) -> Unit = remember {
        { images, index ->
            fullScreenState = FullScreenImageState(images, index)
        }
    }
    val spacing = LocalSocialSpacing.current

    Column(modifier = modifier.fillMaxSize().background(colors.systemBackground)) {
        TopAppBar(
            title = { Text("我的", fontWeight = FontWeight.SemiBold) },
            actions = {
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            },
        )

        when (val state = uiState) {
            is ProfileUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("加载中…", color = colors.tertiaryLabel)
            }
            is ProfileUiState.Empty -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("暂无资料", color = colors.tertiaryLabel)
            }
            is ProfileUiState.Success -> {
                val tweets by viewModel.tweetsFlow.collectAsStateWithLifecycle()
                ProfileHeader(
                    state = state,
                    tweetsCount = tweets.size,
                    onFollowingClick = { onNavigateToFollowList(FollowListType.FOLLOWING) },
                    onFollowersClick = { onNavigateToFollowList(FollowListType.FOLLOWERS) },
                    avatarUrl = ProfileUtils.avatarUriFromSeed(state.account.avatarSeed),
                    imageLoader = viewModel.imageLoader,
                )
                // #146 A/E 场景 6 followRecommend：推荐关注入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .socialClickable { onNavigateToFollowList(FollowListType.RECOMMENDED) }
                        .padding(horizontal = spacing.lg, vertical = spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("推荐关注", fontWeight = FontWeight.Medium, color = colors.label)
                    Text("基于你的兴趣 >", color = colors.tertiaryLabel)
                }
                SocialDivider()
                CapsuleTab(
                    tabs = ProfileTab.values().map { it.label },
                    selectedIndex = selectedTab.ordinal,
                    onTabSelected = { viewModel.selectTab(ProfileTab.values()[it]) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
                )
                when (selectedTab) {
                    ProfileTab.TWEETS -> TweetsTab(
                        viewModel = viewModel,
                        imageLoader = viewModel.imageLoader,
                        onImageClick = onImageClick,
                    )
                    ProfileTab.MEDIA -> MediaTab(
                        viewModel = viewModel,
                        onImageClick = onImageClick,
                    )
                    // #138：LIKES Tab 展示已点赞推文，而非硬编码空占位符
                    ProfileTab.LIKES -> LikesTab(
                        viewModel = viewModel,
                        imageLoader = viewModel.imageLoader,
                        onImageClick = onImageClick,
                    )
                }
            }
        }

        // #8：全屏大图查看器（Dialog，独立窗口，不影响列表布局）
        fullScreenState?.let { state ->
            ProfileFullScreenImage(
                images = state.images,
                initialIndex = state.index,
                imageLoader = viewModel.imageLoader,
                onDismiss = { fullScreenState = null },
            )
        }
    }
}

@Composable
private fun ProfileHeader(
    state: ProfileUiState.Success,
    tweetsCount: Int,
    onFollowingClick: () -> Unit,
    onFollowersClick: () -> Unit,
    avatarUrl: String,
    imageLoader: coil.ImageLoader,
) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    val account = state.account
    // #20：资料区分段留白，头像与名称行顶部对齐，增加呼吸感与层次
    Column(Modifier.fillMaxWidth().padding(spacing.lg)) {
        Row(verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(avatarUrl)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(CircleShape)
                    .background(colors.tertiaryBackground),
            )
            Spacer(Modifier.width(spacing.lg))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = account.displayName.ifBlank { "我" },
                    style = typography.title2,
                    color = colors.label,
                )
                Text(
                    text = "@${account.username}",
                    style = typography.callout,
                    color = colors.tertiaryLabel,
                )
            }
        }
        if (account.bio.isNotBlank()) {
            // #20：名称行↔bio 间距 16dp
            Spacer(Modifier.height(spacing.lg))
            Text(
                text = account.bio,
                style = typography.body,
                color = colors.label,
            )
        }
        if (account.profession.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = account.profession,
                style = typography.subheadline,
                color = colors.secondaryLabel,
            )
        }
        // #20：bio↔统计行间距提升到 20dp；#32：统计行间距 24dp→20dp
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatItem(label = "推文", value = ProfileUtils.formatCount(tweetsCount))
            StatItem(
                label = "关注",
                value = ProfileUtils.formatCount(state.followingCount),
                onClick = onFollowingClick,
            )
            StatItem(
                label = "粉丝",
                value = ProfileUtils.formatCount(state.followersCount),
                onClick = onFollowersClick,
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, onClick: (() -> Unit)? = null) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    // #21：可点击统计项加水波纹按压反馈
    val mod = if (onClick != null) Modifier.socialClickable { onClick() } else Modifier
    // #20/#30：value 用 headline（17sp SemiBold），label 用 caption1，间距 6dp
    Row(mod, verticalAlignment = Alignment.Bottom) {
        Text(value, style = typography.headline, color = colors.label)
        Spacer(Modifier.width(6.dp))
        Text(label, style = typography.caption1, color = colors.tertiaryLabel)
    }
}

@Composable
private fun TweetsTab(
    viewModel: ProfileViewModel,
    imageLoader: coil.ImageLoader,
    onImageClick: (List<String>, Int) -> Unit,
) {
    val tweets by viewModel.tweetsFlow.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedTweetIds.collectAsStateWithLifecycle()
    if (tweets.isEmpty()) {
        EmptyTab(text = "还没有发布过推文")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tweets, key = { it.id }) { tweet ->
            // #23：列表项进场/位移动画
            Column(Modifier.animateItem()) {
                ProfileTweetRow(
                    tweet = tweet,
                    imageLoader = imageLoader,
                    isLiked = tweet.id in likedIds,
                    onLikeClick = { viewModel.toggleLike(tweet.id) },
                    onCommentClick = { viewModel.commentTweet(tweet.id) },
                    onRetweetClick = { viewModel.retweetTweet(tweet.id) },
                    onImageClick = { uri -> onImageClick(listOf(uri), 0) },
                )
                SocialDivider(thickness = 0.5.dp)
            }
        }
    }
}

/**
 * 个人主页推文行（#8）：文本/图片/相对时间 + 互动栏（评论/转发/点赞）。
 *
 * 整行可点击（水波纹视觉反馈）；图片点击进入全屏大图；
 * 互动按钮对齐 feature-feed TweetCard 的图标与布局。
 */
@Composable
private fun ProfileTweetRow(
    tweet: TweetEntity,
    imageLoader: coil.ImageLoader,
    isLiked: Boolean,
    onLikeClick: () -> Unit,
    onCommentClick: () -> Unit,
    onRetweetClick: () -> Unit,
    onImageClick: (String) -> Unit,
) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.systemBackground),
    ) {
        // TODO: 后续接入推文详情页跳转
        Column(Modifier.fillMaxWidth().padding(spacing.lg)) {
            Text(
                text = ProfileUtils.formatRelativeTime(tweet.createdAt),
                style = typography.caption2,
                color = colors.tertiaryLabel,
            )
            Spacer(Modifier.height(spacing.xs))
            if (tweet.text.isNotBlank()) {
                Text(
                    text = tweet.text,
                    style = typography.callout,
                    color = colors.label,
                )
            }
            ProfileUtils.toImageUri(tweet.mediaPath)?.let { uri ->
                Spacer(Modifier.height(spacing.sm))
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(uri).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        // #8：图片可点击进入全屏大图查看器
                        .socialClickable { onImageClick(uri) },
                )
            }
            Spacer(Modifier.height(spacing.sm))
            // #8：互动栏，对齐 feature-feed TweetCard（评论/转发/点赞，SpaceBetween 布局）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InteractionButton(
                    icon = Icons.Filled.ChatBubbleOutline,
                    count = tweet.commentCount,
                    tint = colors.tertiaryLabel,
                    contentDescription = "评论",
                    onClick = onCommentClick,
                )
                InteractionButton(
                    icon = Icons.Filled.Repeat,
                    count = tweet.retweetCount,
                    tint = colors.tertiaryLabel,
                    contentDescription = "转发",
                    onClick = onRetweetClick,
                )
                InteractionButton(
                    icon = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    count = tweet.likeCount,
                    tint = if (isLiked) colors.systemRed else colors.tertiaryLabel,
                    contentDescription = if (isLiked) "取消点赞" else "点赞",
                    onClick = onLikeClick,
                    // #3：点赞触感反馈，与信息流一致
                    hapticOnPress = true,
                )
            }
        }
    }
}

/**
 * 互动按钮：图标 + 计数（count <= 0 时不显示数字），对齐 feature-feed InteractionButton。
 *
 * 触控热区 ≥44dp 满足无障碍标准；socialClickable 提供水波纹按压反馈。
 */
@Composable
private fun InteractionButton(
    icon: ImageVector,
    count: Int,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
    hapticOnPress: Boolean = false,
) {
    val typography = LocalSocialTypography.current
    val hapticFeedback = LocalHapticFeedback.current
    val spacing = LocalSocialSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .minTouchTarget()
            .socialClickable(onClick = {
                if (hapticOnPress) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                onClick()
            })
            .padding(horizontal = spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        if (count > 0) {
            Spacer(Modifier.width(spacing.xs))
            Text(
                text = ProfileUtils.formatCount(count),
                style = typography.caption1,
                color = tint,
            )
        }
    }
}

@Composable
private fun MediaTab(
    viewModel: ProfileViewModel,
    onImageClick: (List<String>, Int) -> Unit,
) {
    val media by viewModel.mediaTweetsFlow.collectAsStateWithLifecycle()
    if (media.isEmpty()) {
        EmptyTab(text = "还没有媒体内容")
        return
    }
    // #8：收集所有媒体 URI，点击网格项时传入列表与下标，支持全屏查看器左右切换
    val uris = remember(media) { media.mapNotNull { ProfileUtils.toImageUri(it.mediaPath) } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(2.dp),
    ) {
        // #8：直接以 uris 为数据源并使用 itemsIndexed 精确定位点击下标，
        // 避免重复 URI 时 indexOf 返回首个匹配导致下标错位
        itemsIndexed(uris) { index, uri ->
            Box(
                Modifier.padding(2.dp).height(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(socialColors().tertiaryBackground)
                    // #8：网格项可点击，打开全屏大图查看器
                    .socialClickable { onImageClick(uris, index) },
            ) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(uri).crossfade(true).build(),
                    imageLoader = viewModel.imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun EmptyTab(text: String) {
    val colors = socialColors()
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(text, color = colors.tertiaryLabel, textAlign = TextAlign.Center)
    }
}

/**
 * #138：已点赞推文 Tab。
 *
 * 展示 [ProfileViewModel.likedTweetsFlow] 中的推文，复用 [ProfileTweetRow] 渲染。
 */
@Composable
private fun LikesTab(
    viewModel: ProfileViewModel,
    imageLoader: coil.ImageLoader,
    onImageClick: (List<String>, Int) -> Unit,
) {
    val likedTweets by viewModel.likedTweetsFlow.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedTweetIds.collectAsStateWithLifecycle()
    if (likedTweets.isEmpty()) {
        EmptyTab(text = "还没有喜欢的内容")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(likedTweets, key = { it.id }) { tweet ->
            Column(Modifier.animateItem()) {
                ProfileTweetRow(
                    tweet = tweet,
                    imageLoader = imageLoader,
                    isLiked = tweet.id in likedIds,
                    onLikeClick = { viewModel.toggleLike(tweet.id) },
                    onCommentClick = { viewModel.commentTweet(tweet.id) },
                    onRetweetClick = { viewModel.retweetTweet(tweet.id) },
                    onImageClick = { uri -> onImageClick(listOf(uri), 0) },
                )
                SocialDivider(thickness = 0.5.dp)
            }
        }
    }
}

