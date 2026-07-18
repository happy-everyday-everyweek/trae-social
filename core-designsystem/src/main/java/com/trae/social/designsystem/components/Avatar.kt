package com.trae.social.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.coerceAtLeast
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.trae.social.designsystem.theme.LocalSocialColors

/**
 * 圆形头像组件。
 *
 * 使用 Coil 加载网络/本地图片，圆形裁剪；加载与失败态显示 shimmer 占位。
 *
 * #196：[url] 为空时不进入 Coil error 态（避免无限 shimmer），直接渲染 systemBlue
 * 占位圆形；[size] 通过 [coerceAtLeast] 保证 >= 1.dp，避免 RoundedCornerShape 收到
 * 负值导致未定义行为。
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
    val safeSize = size.coerceAtLeast(1.dp)
    val colors = LocalSocialColors.current

    // url 为空时直接渲染占位圆形，避免 Coil 立即进入 error 态导致无限 shimmer
    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier
                .size(safeSize)
                .clip(CircleShape)
                .background(colors.secondaryBackground)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    }
                ),
        )
        return
    }

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
            .size(safeSize)
            .clip(CircleShape),
        loading = {
            LoadingShimmer(modifier = Modifier.fillMaxSize(), cornerRadius = safeSize / 2)
        },
        error = {
            // 加载失败也用静态占位，避免无限 shimmer（与 url 为空一致）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(colors.secondaryBackground),
            )
        },
    )
}
