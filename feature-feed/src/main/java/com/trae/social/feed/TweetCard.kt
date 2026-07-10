package com.trae.social.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Repeat
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.components.socialClickable
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 推文卡片。
 *
 * 顶部行：头像 + 显示名 + 用户名 + 相对时间 + 更多按钮；
 * 文本：超 280 字符折叠，附"展开全文"；
 * 图片：Coil 加载 SVG/PNG，圆角 12dp，最大高度 400dp，点击进入大图；
 * 互动栏：评论 / 转发 / 点赞 / 收藏；
 * AI 推文：头像右下角小蓝点。
 *
 * @param data 推文 + 作者信息
 * @param isLiked 当前是否已点赞（由 ViewModel 维护）
 * @param isBookmarked 当前是否已收藏
 * @param imageLoader 信息流专用 ImageLoader（含 SVG 解码）
 * @param onImageClick 点击图片回调，传入图片 URI
 * @param onLikeClick 点赞回调
 * @param onCommentClick 评论回调
 * @param onRetweetClick 转发回调
 * @param onBookmarkClick 收藏回调
 */
@Composable
fun TweetCard(
    data: TweetWithAuthor,
    isLiked: Boolean,
    isBookmarked: Boolean,
    imageLoader: ImageLoader,
    onImageClick: (String) -> Unit,
    onLikeClick: () -> Unit,
    onCommentClick: () -> Unit,
    onRetweetClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val tweet = data.tweet
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
            .padding(horizontal = 16.dp, vertical = 16.dp),
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
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = data.authorName,
                        style = typography.body.copy(fontWeight = FontWeight.Bold),
                        color = colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
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
                        maxLines = 1,
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { moreMenuExpanded = true },
                    // #33：触控热区 32→44，满足 ≥44dp 无障碍标准
                    modifier = Modifier.size(44.dp),
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
                            Toast.makeText(context, "已减少推荐类似内容", Toast.LENGTH_SHORT).show()
                            moreMenuExpanded = false
                        },
                    )
                }
            }
        }

        // #19：顶部行↔正文间距 8→12
        Spacer(Modifier.height(12.dp))

        // 文本（超 280 字折叠）
        TweetText(text = tweet.text, labelColor = colors.label)

        // 图片
        val imageUri = FeedUtils.toImageUri(tweet.mediaPath)
        if (imageUri != null) {
            // #19：正文↔图片间距 8→12
            Spacer(Modifier.height(12.dp))
            val request = remember(imageUri, context) {
                ImageRequest.Builder(context)
                    .data(imageUri)
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = "推文图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onImageClick(imageUri) },
            )
        }

        // #19：图片↔互动栏间距 8→16，拉开层次
        Spacer(Modifier.height(16.dp))

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
            )
            InteractionButton(
                icon = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                count = null,
                tint = if (isBookmarked) colors.systemBlue else colors.tertiaryLabel,
                contentDescription = if (isBookmarked) "取消收藏" else "收藏",
                onClick = onBookmarkClick,
            )
        }
    }
    SocialDivider(thickness = 0.5.dp)
}

/**
 * 推文文本：超过 280 字符折叠，附"展开全文"/"收起"切换。
 */
@Composable
private fun TweetText(
    text: String,
    labelColor: Color,
) {
    val typography = LocalSocialTypography.current
    val limit = 280
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val needCollapse = text.length > limit
    val displayText = if (needCollapse && !expanded) text.take(limit) else text

    Column {
        Text(
            text = displayText,
            style = typography.body,
            color = labelColor,
        )
        if (needCollapse) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (expanded) "收起" else "展开全文",
                style = typography.caption1.copy(fontWeight = FontWeight.SemiBold),
                color = LocalSocialColors.current.systemBlue,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

/**
 * 互动按钮：图标 + 计数（count 为 null 时不显示数字）。
 */
@Composable
private fun InteractionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int?,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val typography = LocalSocialTypography.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            // #19/#33：触控热区 ≥44dp，满足无障碍最低标准，降低误触
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            // #21：水波纹按压反馈
            .socialClickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = formatCount(count),
                style = typography.caption1,
                color = tint,
            )
        }
    }
}

/**
 * 数量格式化：超 1 万显示为 "1.2万"。
 */
private fun formatCount(count: Int): String {
    return if (count >= 10000) {
        val wan = count / 10000.0
        String.format("%.1f万", wan)
    } else {
        count.toString()
    }
}

/**
 * 信息流头像：圆形 + SVG 解码 + AI 蓝点标识。
 *
 * 复刻 [com.trae.social.designsystem.components.Avatar] 视觉，但注入 feed 专用
 * ImageLoader 以支持 SVG。#14：已移除头像右下角 AI 蓝点标识。
 */
@Composable
private fun FeedAvatar(
    avatarSeed: String,
    imageLoader: ImageLoader,
    isAiGenerated: Boolean,
    modifier: Modifier = Modifier,
) {
    // #14：colors 原用于 AI 蓝点标识背景，蓝点移除后不再需要
    val context = LocalContext.current
    val url = remember(avatarSeed) { FeedUtils.avatarUriFromSeed(avatarSeed) }
    val request = remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .build()
    }

    Box(modifier = modifier, contentAlignment = Alignment.BottomEnd) {
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = "头像",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape),
        )
        // #14：去除信息流头像右下角的 6dp AI 蓝点标识。
        // 本应用所有非用户推文均为 AI 生成，逐条标记价值有限且破坏拟真社交沉浸感。
        // 透明度声明（RISK-12）改为引导页 DisclaimerCard 一次性告知，符合拟人化预期。
    }
}
