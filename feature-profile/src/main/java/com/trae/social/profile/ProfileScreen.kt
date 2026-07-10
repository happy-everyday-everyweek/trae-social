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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import com.trae.social.designsystem.theme.LocalSocialTypography
import com.trae.social.designsystem.theme.socialColors

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
                SocialDivider()
                CapsuleTab(
                    tabs = ProfileTab.values().map { it.label },
                    selectedIndex = selectedTab.ordinal,
                    onTabSelected = { viewModel.selectTab(ProfileTab.values()[it]) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                when (selectedTab) {
                    ProfileTab.TWEETS -> TweetsTab(
                        viewModel = viewModel,
                        imageLoader = viewModel.imageLoader,
                    )
                    ProfileTab.MEDIA -> MediaTab(viewModel = viewModel)
                    ProfileTab.LIKES -> EmptyTab(text = "还没有喜欢的内容")
                }
            }
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
    val account = state.account
    // #20：资料区分段留白，头像与名称行顶部对齐，增加呼吸感与层次
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
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
            Spacer(Modifier.width(16.dp))
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
            Spacer(Modifier.height(16.dp))
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
) {
    val tweets by viewModel.tweetsFlow.collectAsStateWithLifecycle()
    if (tweets.isEmpty()) {
        EmptyTab(text = "还没有发布过推文")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tweets, key = { it.id }) { tweet ->
            ProfileTweetRow(tweet = tweet, imageLoader = imageLoader)
            SocialDivider(thickness = 0.5.dp)
        }
    }
}

@Composable
private fun ProfileTweetRow(tweet: TweetEntity, imageLoader: coil.ImageLoader) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = ProfileUtils.formatRelativeTime(tweet.createdAt),
            style = typography.caption2,
            color = colors.tertiaryLabel,
        )
        Spacer(Modifier.height(4.dp))
        if (tweet.text.isNotBlank()) {
            Text(
                text = tweet.text,
                style = typography.callout,
                color = colors.label,
            )
        }
        ProfileUtils.toImageUri(tweet.mediaPath)?.let { uri ->
            Spacer(Modifier.height(8.dp))
            AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(uri).crossfade(true).build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(200.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            InteractionCount("评论", tweet.commentCount)
            InteractionCount("转发", tweet.retweetCount)
            InteractionCount("点赞", tweet.likeCount)
        }
    }
}

@Composable
private fun InteractionCount(label: String, count: Int) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ProfileUtils.formatCount(count), color = colors.secondaryLabel, style = typography.footnote)
        Spacer(Modifier.width(2.dp))
        Text(label, color = colors.tertiaryLabel, style = typography.footnote)
    }
}

@Composable
private fun MediaTab(viewModel: ProfileViewModel) {
    val media by viewModel.mediaTweetsFlow.collectAsStateWithLifecycle()
    if (media.isEmpty()) {
        EmptyTab(text = "还没有媒体内容")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(2.dp),
    ) {
        items(media, key = { it.id }) { tweet ->
            val uri = ProfileUtils.toImageUri(tweet.mediaPath)
            Box(
                Modifier.padding(2.dp).height(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(socialColors().tertiaryBackground),
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

