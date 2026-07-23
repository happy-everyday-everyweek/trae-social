package com.trae.social.feed

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialSpacing
import com.trae.social.designsystem.theme.LocalSocialTypography
import com.trae.social.designsystem.theme.minTouchTarget

/**
 * 推文卡片。
 *
 * 顶部行：头像 + 显示名 + 用户名 + 相对时间 + 更多按钮；
 * 文本：超 280 字符折叠，附"展开全文"；
 * 图片：Coil 加载 SVG/PNG，圆角 12dp，最大高度 400dp，点击进入大图；
 * 互动栏：评论 / 转发 / 点赞 / 收藏；
 * AI 推文：头像右下角小蓝点。
 *
 * #323：本文件仅保留 TweetCard 入口编排逻辑。子组件已按职责拆分到独立文件：
 * - [TweetMediaGrid] / [TweetMediaCell] → TweetMedia.kt
 * - [TweetText] → TweetText.kt
 * - [InteractionButton] / [formatCount] → TweetInteractionButton.kt
 * - [FeedAvatar] → TweetAvatar.kt
 *
 * @param data 推文 + 作者信息
 * @param isLiked 当前是否已点赞（由 ViewModel 维护）
 * @param isBookmarked 当前是否已收藏
 * @param imageLoader 信息流专用 ImageLoader（含 SVG 解码）
 * @param onImageClick 点击图片回调，传入该推文全部图片 URI 列表与被点击图片下标
 * @param onLikeClick 点赞回调
 * @param onCommentClick 评论回调
 * @param onRetweetClick 转发回调
 * @param onBookmarkClick 收藏回调
 * @param onNotInterestedClick 不感兴趣回调（#142：隐藏该推文）
 */
@Composable
fun TweetCard(
    data: TweetWithAuthor,
    isLiked: Boolean,
    isBookmarked: Boolean,
    imageLoader: ImageLoader,
    onImageClick: (List<String>, Int) -> Unit,
    onLikeClick: () -> Unit,
    onCommentClick: () -> Unit,
    onRetweetClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onNotInterestedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val tweet = data.tweet
    // #212 review：下拉菜单为瞬态 UI，旋转后自动重新展开体验突兀，改回 remember 不持久化
    var moreMenuExpanded by remember { mutableStateOf(false) }

    // 显示的点赞数：DB likeCount 是唯一数据源（IMPL-11）。
    // 乐观更新已通过 updateLikeCount(+1) 写入 DB，PagingSource 重发后 tweet.likeCount
    // 已包含 +1，此处不再额外 +1，否则会双重计数。
    val displayLikeCount = tweet.likeCount

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.systemBackground)
            // #19：卡片垂直留白 12→16，提升呼吸感
            .padding(spacing.lg)
            // #33：列表项整条语义合并，TalkBack 一次朗读整条推文（作者/正文/互动按钮），
            // 而非逐项零碎朗读。子节点（IconButton 等）仍保留自身 click 语义供聚焦操作。
            .semantics(mergeDescendants = true) {},
    ) {
        // 顶部行：头像 + 名称 + 时间 + 更多
        Row(verticalAlignment = Alignment.CenterVertically) {
            FeedAvatar(
                avatarSeed = data.authorAvatarSeed,
                imageLoader = imageLoader,
                isAiGenerated = tweet.isAiGenerated,
                modifier = Modifier.size(36.dp),
            )
            // #19：头像后间距统一为 4 倍数 12dp
            Spacer(Modifier.width(spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = data.authorName,
                        style = typography.body.copy(fontWeight = FontWeight.Bold),
                        color = colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(spacing.xs))
                    Text(
                        text = "@${data.authorUsername}",
                        style = typography.caption1,
                        color = colors.tertiaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = " · ",
                        style = typography.caption1,
                        color = colors.tertiaryLabel,
                    )
                    Text(
                        text = FeedUtils.formatRelativeTime(tweet.createdAt),
                        style = typography.caption1,
                        color = colors.tertiaryLabel,
                        // #33：超大字号时配合 ellipsis 防溢出
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { moreMenuExpanded = true },
                    modifier = Modifier.minTouchTarget(),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = "更多",
                        tint = colors.tertiaryLabel,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = moreMenuExpanded,
                    onDismissRequest = { moreMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("复制文本") },
                        onClick = {
                            clipboardManager.setText(AnnotatedString(tweet.text))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            moreMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("分享") },
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, tweet.text)
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(shareIntent, "分享推文"))
                            }.onFailure {
                                Toast.makeText(context, "无可用的分享应用", Toast.LENGTH_SHORT).show()
                            }
                            moreMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("不感兴趣") },
                        onClick = {
                            // #142：回调 ViewModel 隐藏该推文，而非仅 Toast
                            onNotInterestedClick()
                            Toast.makeText(context, "已减少推荐类似内容", Toast.LENGTH_SHORT).show()
                            moreMenuExpanded = false
                        },
                    )
                }
            }
        }

        // #19：顶部行↔正文间距 8→12
        Spacer(Modifier.height(spacing.md))

        // 文本（超 280 字折叠）
        TweetText(text = tweet.text, labelColor = colors.label)

        // 图片（#4：按数量差异化布局，1 大图 / 2 并排 / 3 主次 / 4 网格，多图显示页码指示器）
        val imageUris = remember(tweet.mediaPath) { FeedUtils.toImageUriList(tweet.mediaPath) }
        if (imageUris.isNotEmpty()) {
            // #19：正文↔图片间距 8→12
            Spacer(Modifier.height(spacing.md))
            TweetMediaGrid(
                imageUris = imageUris,
                imageLoader = imageLoader,
                onCellClick = { index -> onImageClick(imageUris, index) },
            )
        }

        // #19：图片↔互动栏间距 8→16，拉开层次
        Spacer(Modifier.height(spacing.lg))

        // 互动栏
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
                count = displayLikeCount,
                tint = if (isLiked) colors.systemRed else colors.tertiaryLabel,
                contentDescription = if (isLiked) "取消点赞" else "点赞",
                onClick = onLikeClick,
                // #3：点赞心跳弹跳 + 触感反馈
                bounceWhenActive = true,
                active = isLiked,
                hapticOnPress = true,
            )
            InteractionButton(
                icon = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                count = null,
                tint = if (isBookmarked) colors.systemBlue else colors.tertiaryLabel,
                contentDescription = if (isBookmarked) "取消收藏" else "收藏",
                onClick = onBookmarkClick,
                // #3：收藏触感反馈
                hapticOnPress = true,
            )
        }
    }
    SocialDivider(thickness = 0.5.dp)
}
