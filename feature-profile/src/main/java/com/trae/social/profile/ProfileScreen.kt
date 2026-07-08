package com.trae.social.profile

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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.designsystem.components.CapsuleTab
import com.trae.social.designsystem.components.SocialDivider
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
    val account = state.account
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Column {
                Text(
                    text = account.displayName.ifBlank { "我" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.label,
                )
                Text(
                    text = "@${account.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.tertiaryLabel,
                )
            }
        }
        if (account.bio.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = account.bio,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.label,
            )
        }
        if (account.profession.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = account.profession,
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryLabel,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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
    val mod = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    Row(mod, verticalAlignment = Alignment.Bottom) {
        Text(value, fontWeight = FontWeight.Bold, color = colors.label, fontSize = 16.sp)
        Spacer(Modifier.width(4.dp))
        Text(label, color = colors.tertiaryLabel, fontSize = 13.sp)
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
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = ProfileUtils.formatRelativeTime(tweet.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = colors.tertiaryLabel,
        )
        Spacer(Modifier.height(4.dp))
        if (tweet.text.isNotBlank()) {
            Text(
                text = tweet.text,
                style = MaterialTheme.typography.bodyMedium,
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ProfileUtils.formatCount(count), color = colors.secondaryLabel, fontSize = 13.sp)
        Spacer(Modifier.width(2.dp))
        Text(label, color = colors.tertiaryLabel, fontSize = 13.sp)
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

