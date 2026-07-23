package com.trae.social.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 信息流头像：圆形 + SVG 解码 + AI 蓝点标识。
 *
 * 复刻 [com.trae.social.designsystem.components.Avatar] 视觉，但注入 feed 专用
 * ImageLoader 以支持 SVG。#14：已移除头像右下角 AI 蓝点标识。
 *
 * #323：从原 TweetCard.kt 拆分到此文件，TweetCard.kt 作为入口仅保留编排逻辑。
 */
@Composable
internal fun FeedAvatar(
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
