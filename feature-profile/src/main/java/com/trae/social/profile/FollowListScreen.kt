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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.theme.socialColors
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 关注/粉丝列表页（IMPL-2）。
 *
 * @param type 列表类型（关注 / 粉丝）
 * @param onBack 返回
 * @param onAccountClick 点击账号项回调（#11：进入账号详情或主页）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowListScreen(
    type: FollowListType,
    onBack: () -> Unit,
    onAccountClick: (AccountEntity) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FollowListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val followingIds by viewModel.followingIds.collectAsStateWithLifecycle()
    val imageLoader = viewModel.imageLoader
    val colors = socialColors()

    LaunchedEffect(type) { viewModel.load(type) }

    Column(modifier.fillMaxSize().background(colors.systemBackground)) {
        TopAppBar(
            title = { Text(type.title, fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        when (val state = uiState) {
            is FollowListUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("加载中…", color = colors.tertiaryLabel)
            }
            is FollowListUiState.Success -> {
                if (state.accounts.isEmpty()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("暂无${type.title}", color = colors.tertiaryLabel)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.accounts, key = { it.id }) { account ->
                            FollowAccountRow(
                                account = account,
                                imageLoader = imageLoader,
                                isFollowing = account.id in followingIds,
                                onToggleFollow = { viewModel.toggleFollow(type, account.id) },
                                // #11：点击账号项触发回调，进入账号详情
                                onAccountClick = { onAccountClick(account) },
                            )
                            SocialDivider(thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 关注/粉丝列表项。
 *
 * - 整行可点击：触发 [onAccountClick] 回调进入账号详情（#11：替代原占位 Toast）
 * - 右侧关注/已关注按钮：真实写库并刷新列表
 */
@Composable
private fun FollowAccountRow(
    account: AccountEntity,
    imageLoader: coil.ImageLoader,
    isFollowing: Boolean,
    onToggleFollow: () -> Unit,
    onAccountClick: () -> Unit,
) {
    val colors = socialColors()
    val typography = LocalSocialTypography.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // #11：整行可点击，触发账号详情回调（替代原占位 Toast）
            .clickable { onAccountClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(ProfileUtils.avatarUriFromSeed(account.avatarSeed))
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .background(colors.tertiaryBackground),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    account.displayName.ifBlank { account.username },
                    fontWeight = FontWeight.Medium,
                    color = colors.label,
                    style = typography.body,
                )
                Text(
                    "@${account.username}",
                    color = colors.tertiaryLabel,
                    style = typography.subheadline,
                )
            }
        }
        OutlinedButton(
            onClick = onToggleFollow,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)),
        ) {
            Text(
                text = if (isFollowing) "已关注" else "关注",
                style = typography.subheadline,
                color = if (isFollowing) colors.tertiaryLabel else colors.systemBlue,
            )
        }
    }
}
