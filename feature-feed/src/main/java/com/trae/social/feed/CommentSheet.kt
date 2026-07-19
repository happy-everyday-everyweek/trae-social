package com.trae.social.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.components.SocialSheet
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialSpacing
import com.trae.social.designsystem.theme.LocalSocialTypography
import java.util.UUID

/**
 * 评论弹层。
 *
 * - 顶部：原推文摘要（作者 + 文本前 50 字 + 图片缩略图）
 * - 中部：评论列表（LazyColumn），打开时通过 [loadComments] 从持久化评论表加载
 * - 底部：输入框 + 发送按钮
 *
 * 发送评论后立即本地追加（乐观展示），同时经 [onSendComment] 交由 ViewModel 持久化；
 * 下次打开时 [loadComments] 会返回包含已持久化评论的完整列表。
 *
 * @param loadComments 挂起函数，返回该推文已持久化的评论列表（按时间升序）
 */
@Composable
fun CommentSheet(
    tweet: TweetWithAuthor,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
    onSendComment: (String) -> Unit,
    loadComments: suspend () -> List<CommentItem>,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    val context = LocalContext.current
    val comments = remember { mutableStateListOf<CommentItem>() }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    // 打开时从 DB 加载已持久化评论（含历史 AI 评论）
    LaunchedEffect(tweet.tweet.id) {
        isLoading = true
        val loaded = loadComments()
        comments.clear()
        comments.addAll(loaded)
        isLoading = false
    }

    SocialSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 560.dp),
        ) {
            // 顶部：原推文摘要
            TweetSummary(
                tweet = tweet,
                imageLoader = imageLoader,
                context = context,
            )
            SocialDivider()
            Text(
                text = "评论 ${comments.size}",
                style = typography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
            )

            // 中部：评论列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = spacing.lg,
                    vertical = spacing.xs,
                ),
            ) {
                if (comments.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = spacing.xl),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (isLoading) "加载中..." else "暂无评论，发表第一条吧",
                                style = typography.callout,
                                color = colors.tertiaryLabel,
                            )
                        }
                    }
                }
                items(comments, key = { it.id }) { comment ->
                    // #23：评论新增项进场动画，新增评论有淡入/位移过渡
                    Column(Modifier.animateItem()) {
                        CommentRow(comment = comment, imageLoader = imageLoader, context = context)
                        Spacer(Modifier.height(spacing.sm))
                    }
                }
            }

            SocialDivider()

            // 底部：输入框 + 发送
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("写评论...") },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.width(spacing.sm))
                IconButton(
                    onClick = {
                        val text = inputText.trim()
                        if (text.isNotEmpty()) {
                            comments.add(
                                CommentItem(
                                    id = UUID.randomUUID().toString(),
                                    // 与 DB 加载一致：user-self 账号 displayName="我"、avatarSeed="user"（与 username 一致，见 PersonaSeeder#ensureUserSelfAccount）
                                    authorName = "我",
                                    authorAvatarSeed = "user",
                                    content = text,
                                    createdAt = System.currentTimeMillis(),
                                )
                            )
                            onSendComment(text)
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (inputText.isNotBlank()) colors.systemBlue else colors.tertiaryLabel,
                    )
                }
            }
        }
    }
}

/**
 * 原推文摘要：作者 + 文本前 50 字 + 图片缩略图。
 */
@Composable
private fun TweetSummary(
    tweet: TweetWithAuthor,
    imageLoader: ImageLoader,
    context: android.content.Context,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    val summary = remember(tweet.tweet.text) {
        if (tweet.tweet.text.length > 50) tweet.tweet.text.take(50) + "..." else tweet.tweet.text
    }
    val avatarUrl = remember(tweet.authorAvatarSeed) {
        FeedUtils.avatarUriFromSeed(tweet.authorAvatarSeed)
    }
    val imageUri = remember(tweet.tweet.mediaPath) {
        FeedUtils.toImageUri(tweet.tweet.mediaPath)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(spacing.lg),
    ) {
        AsyncImage(
            model = remember(avatarUrl, context) {
                ImageRequest.Builder(context).data(avatarUrl).crossfade(true).build()
            },
            imageLoader = imageLoader,
            contentDescription = "头像",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tweet.authorName,
                style = typography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.label,
            )
            Text(
                text = summary,
                style = typography.callout,
                color = colors.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (imageUri != null) {
                Spacer(Modifier.height(6.dp))
                AsyncImage(
                    model = remember(imageUri, context) {
                        ImageRequest.Builder(context).data(imageUri).crossfade(true).build()
                    },
                    imageLoader = imageLoader,
                    contentDescription = "缩略图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 80.dp, height = 60.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}

/**
 * 评论列表项：头像 + 名 + 内容 + 时间。
 */
@Composable
private fun CommentRow(
    comment: CommentItem,
    imageLoader: ImageLoader,
    context: android.content.Context,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    val avatarUrl = remember(comment.authorAvatarSeed) {
        FeedUtils.avatarUriFromSeed(comment.authorAvatarSeed)
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = remember(avatarUrl, context) {
                ImageRequest.Builder(context).data(avatarUrl).crossfade(true).build()
            },
            imageLoader = imageLoader,
            contentDescription = "头像",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.authorName,
                    style = typography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.label,
                )
                Text(
                    text = " · ",
                    style = typography.caption2,
                    color = colors.tertiaryLabel,
                )
                Text(
                    text = FeedUtils.formatRelativeTime(comment.createdAt),
                    style = typography.caption2,
                    color = colors.tertiaryLabel,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = comment.content,
                style = typography.callout,
                color = colors.label,
            )
        }
    }
}
