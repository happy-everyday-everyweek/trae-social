package com.trae.social.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.trae.social.designsystem.components.LoadingShimmer
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography

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
 *
 * #323：从原 TweetCard.kt 拆分到此文件，TweetCard.kt 作为入口仅保留编排逻辑。
 */
@Composable
internal fun TweetMediaGrid(
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
internal fun TweetMediaCell(
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
