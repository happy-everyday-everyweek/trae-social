package com.trae.social.feed

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
import androidx.compose.material.icons.outlined.BrokenImage
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.trae.social.designsystem.components.LoadingShimmer
import com.trae.social.designsystem.components.SocialDivider
import com.trae.social.designsystem.components.socialClickable
import com.trae.social.designsystem.theme.LocalReduceMotion
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialSpacing
import com.trae.social.designsystem.theme.LocalSocialTypography
import com.trae.social.designsystem.theme.minTouchTarget
import java.util.Locale

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

/**
 * 推文图片网格：按图片数量差异化布局（#4 多图信息流）。
 *
 * - 1 张：单大图，最大高度 400dp（保持原信息流视觉）。
 * - 2 张：并排，各 1:1 等权。
 * - 3 张：1 大图（顶部全宽 4:3）+ 下方两小图（各 4:3 等权）。
 * - 4+ 张：2x2 网格（各 1:1）；超过 4 张时仅展示前 4 张，第 4 格叠加 "+N" 角标。
 *
 * 多图（2-4 张）时在右下角叠加页码指示器（图片总数），提示可点击进入大图浏览；
 * 超过 4 张时改由第 4 格的 "+N" 角标作为溢出指示，不再重复叠加页码。
 * 每张图片均可点击，回调中带回被点击图片下标，供大图查看器定位初始页。
 */
@Composable
private fun TweetMediaGrid(
    imageUris: List<String>,
    imageLoader: ImageLoader,
    onCellClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = LocalSocialTypography.current
    val gap = 4.dp
    val corner = 12.dp

    Box(modifier = modifier.fillMaxWidth()) {
        when (imageUris.size) {
            0 -> Unit
            1 -> {
                TweetMediaCell(
                    uri = imageUris[0],
                    imageLoader = imageLoader,
                    contentDescription = "推文图片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .clip(RoundedCornerShape(corner)),
                    onClick = { onCellClick(0) },
                )
            }
            2 -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    imageUris.forEachIndexed { index, uri ->
                        TweetMediaCell(
                            uri = uri,
                            imageLoader = imageLoader,
                            contentDescription = "推文图片 ${index + 1}",
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(corner)),
                            onClick = { onCellClick(index) },
                        )
                    }
                }
            }
            3 -> {
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    TweetMediaCell(
                        uri = imageUris[0],
                        imageLoader = imageLoader,
                        contentDescription = "推文图片 1",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(corner)),
                        onClick = { onCellClick(0) },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TweetMediaCell(
                            uri = imageUris[1],
                            imageLoader = imageLoader,
                            contentDescription = "推文图片 2",
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(4f / 3f)
                                .clip(RoundedCornerShape(corner)),
                            onClick = { onCellClick(1) },
                        )
                        TweetMediaCell(
                            uri = imageUris[2],
                            imageLoader = imageLoader,
                            contentDescription = "推文图片 3",
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(4f / 3f)
                                .clip(RoundedCornerShape(corner)),
                            onClick = { onCellClick(2) },
                        )
                    }
                }
            }
            else -> {
                // 4+ 张：2x2 网格，仅展示前 4 张；超过 4 张时第 4 格叠加 "+N" 角标
                val displayCount = minOf(imageUris.size, 4)
                Column(
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    (0 until displayCount).chunked(2).forEach { rowIndices ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            rowIndices.forEach { index ->
                                val showOverflow = index == 3 && imageUris.size > 4
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f),
                                ) {
                                    TweetMediaCell(
                                        uri = imageUris[index],
                                        imageLoader = imageLoader,
                                        contentDescription = "推文图片 ${index + 1}",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(corner)),
                                        onClick = { onCellClick(index) },
                                    )
                                    if (showOverflow) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.45f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "+${imageUris.size - 4}",
                                                color = Color.White,
                                                style = typography.body,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 多图页码指示器（2-4 张时于右下角叠加图片总数，提示可进入大图浏览）
        if (imageUris.size in 2..4) {
            Text(
                text = imageUris.size.toString(),
                color = Color.White,
                style = typography.caption2,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 推文图片单元：Coil SubcomposeAsyncImage 加载（含 SVG），ContentScale.Crop 填充并响应点击。
 *
 * #24：改用 SubcomposeAsyncImage，加载中展示 shimmer 占位，错误态展示破损图标。
 */
@Composable
private fun TweetMediaCell(
    uri: String,
    imageLoader: ImageLoader,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalSocialColors.current
    val request = remember(uri, context) {
        ImageRequest.Builder(context)
            .data(uri)
            .crossfade(true)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.clickable(onClick = onClick),
        loading = {
            LoadingShimmer(
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 12.dp,
            )
        },
        error = {
            // #24：错误态用静态破损图标，避免 shimmer 误导用户以为仍在加载
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = colors.secondaryLabel,
                    modifier = Modifier.size(32.dp),
                )
            }
        },
    )
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
    val spacing = LocalSocialSpacing.current
    // #33：动态字号边界处理——读取系统 fontScale，超大字号（>1.3f）时对正文加
    // maxLines + ellipsis 防止正文撑爆屏幕（sp 自身随 fontScale 缩放，此处仅做裁剪兜底）。
    val fontScale = LocalConfiguration.current.fontScale
    val bodyMaxLines = if (fontScale > 1.3f) 8 else Int.MAX_VALUE
    val limit = 280
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val needCollapse = text.length > limit
    val displayText = if (needCollapse && !expanded) text.take(limit) else text

    Column(
        modifier = Modifier.animateContentSize(animationSpec = tween(durationMillis = 250)),
    ) {
        Text(
            text = displayText,
            style = typography.body,
            color = labelColor,
            // #33：超大字号时限制最大行数并配合 ellipsis，避免长正文在 1.5x+ 字号下撑爆屏幕
            maxLines = bodyMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (needCollapse) {
            Spacer(Modifier.height(spacing.xs))
            // #24：展开/收起文案用 AnimatedContent 过渡，同一时刻仅渲染一个文案，
            // 避免 Crossfade 过渡期内两个可点击 Text 同时存在
            AnimatedContent(
                targetState = expanded,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "expand_toggle",
            ) { isExpanded ->
                Text(
                    text = if (isExpanded) "收起" else "展开全文",
                    style = typography.caption1.copy(fontWeight = FontWeight.SemiBold),
                    color = LocalSocialColors.current.systemBlue,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
    }
}

/**
 * 互动按钮：图标 + 计数（count 为 null 时不显示数字）。
 *
 * #3：点赞（[bounceWhenActive] + [active]）在 false→true 跳变时做 overshoot 弹跳，
 * 营造心跳反馈；[hapticOnPress] 在按下时触发触感反馈，提升社交鲜活感。
 */
@Composable
private fun InteractionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int?,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
    bounceWhenActive: Boolean = false,
    active: Boolean = false,
    hapticOnPress: Boolean = false,
) {
    val typography = LocalSocialTypography.current
    val spacing = LocalSocialSpacing.current
    val hapticFeedback = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    // #3/#201：仅在 false→true 跳变时弹跳，hasObserved 防止首帧（已点赞项）误触动画
    // - 原先 snapTo(0.6f) → animateTo(1f, MediumBouncy+StiffnessMedium) 起点过低（缩到一半）、
    //   wobble 3+ 次，整个点赞反馈拖沓且过大，与社交帖子"轻盈点赞"调性不符。
    // - 调整为 snapTo(0.8f) → LowBouncy+StiffnessMediumLow ≈ 300ms 收束，一次柔和 overshoot
    //   即稳。Emil "nothing appears from nothing" + Apple "damping 0.8 仅在带速度时"原则：
    //   用户点击本身是带速度的输入，所以保留一次轻 overshoot。
    // - 减弱动效：tween(150, FastOutSlowInEasing) 直接到 1f，无 overshoot，
    //   仍能感知到"图标被按了一下"。
    val scale = remember { Animatable(1f) }
    var hasObserved by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (bounceWhenActive && active && hasObserved) {
            // 两个分支仅 spec 不同，抽出共有的 snap + animateTo 以提升可读性
            val spec = if (reduceMotion) {
                tween<Float>(
                    durationMillis = 150,
                    easing = FastOutSlowInEasing,
                )
            } else {
                spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            }
            scale.snapTo(0.8f)
            scale.animateTo(targetValue = 1f, animationSpec = spec)
        }
        hasObserved = true
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .minTouchTarget()
            // #21：水波纹按压反馈
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
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(spacing.xs))
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
 *
 * 显式指定 [Locale.ROOT]：避免在某些语言环境（如德语/法语区）下默认 Locale
 * 使用逗号作为小数分隔符，导致输出 "1,2万" 与中文语境不一致。
 */
private fun formatCount(count: Int): String {
    return if (count >= 10000) {
        val wan = count / 10000.0
        String.format(Locale.ROOT, "%.1f万", wan)
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
