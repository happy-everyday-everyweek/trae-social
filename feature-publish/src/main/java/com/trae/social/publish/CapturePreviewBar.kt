package com.trae.social.publish

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trae.social.designsystem.theme.MinTouchTargetSize
import com.trae.social.designsystem.theme.LocalSocialColors

/**
 * 底部实时预览栏（SubTask 14.3）。
 *
 * 拍照/选图后的缩略图横向列表，最多 [PublishViewModel.MAX_CAPTURES] 张：
 * - 64dp 圆角 8dp 缩略图；
 * - 每张右上角 16dp 删除按钮；
 * - 当前选中张以 systemBlue 边框高亮；
 * - 点击缩略图触发进入编辑/全屏查看。
 *
 * @param captures 图片路径列表
 * @param selectedIndex 当前选中索引（高亮），-1 表示无选中
 * @param onItemSelected 点击缩略图回调
 * @param onItemRemoved 删除回调
 */
@Composable
fun CapturePreviewBar(
    captures: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    onItemRemoved: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(captures, key = { _, path -> path }) { index, path ->
            val selected = index == selectedIndex
            val borderColor = if (selected) colors.systemBlue else Color.Transparent
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.tertiaryBackground)
                    .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
                    .clickable { onItemSelected(index) },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = path,
                    contentDescription = "缩略图 ${index + 1}",
                    modifier = Modifier.size(64.dp),
                )
                // 右上角删除按钮
                // #153：可视尺寸保持 16dp，触控热区扩展到 44dp（MinTouchTargetSize）
                // 满足 WCAG 2.5.5 / Apple HIG 44dp 最低触控目标标准，避免误触缩略图本身
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(MinTouchTargetSize)
                        .clickable { onItemRemoved(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "删除",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}
