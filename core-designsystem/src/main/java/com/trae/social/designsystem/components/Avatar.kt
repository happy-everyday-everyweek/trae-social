package com.trae.social.designsystem.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/**
 * 圆形头像组件。
 *
 * 使用 Coil 加载网络/本地图片，圆形裁剪；加载与失败态显示 shimmer 占位。
 *
 * @param url 图片地址，为空时直接显示占位
 * @param size 头像直径，默认 44dp
 * @param modifier 外部修饰符
 * @param contentDescription 无障碍描述
 */
@Composable
fun Avatar(
    url: String?,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val request = remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .build()
    }

    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        loading = {
            LoadingShimmer(modifier = Modifier.fillMaxSize(), cornerRadius = size / 2)
        },
        error = {
            LoadingShimmer(modifier = Modifier.fillMaxSize(), cornerRadius = size / 2)
        },
    )
}
