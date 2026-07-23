package com.trae.social.publish

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.trae.social.designsystem.theme.LocalSocialColors
import com.trae.social.designsystem.theme.LocalSocialTypography

/**
 * 滤镜横向选择条：圆形缩略图 + 标签。
 *
 * IMPL-36：缩略图从 64×64 降到 32×32，降低 5 个 preset 累计内存占用。
 */
@Composable
internal fun FilterRow(
    selected: FilterPreset,
    onSelect: (FilterPreset) -> Unit,
    sourceBitmap: Bitmap,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSocialColors.current
    val typography = LocalSocialTypography.current

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(FilterPreset.values().toList()) { preset ->
            val isSelected = preset == selected
            // IMPL-36：32×32 缩略图，大幅降低内存
            val thumb = remember(preset, sourceBitmap) {
                runCatching {
                    val small = Bitmap.createScaledBitmap(sourceBitmap, 32, 32, false)
                    preset.apply(small)
                }.getOrDefault(sourceBitmap)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 0.dp,
                            color = if (isSelected) colors.systemBlue else androidx.compose.ui.graphics.Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable { onSelect(preset) },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = preset.label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(CircleShape),
                    )
                }
                Text(
                    text = preset.label,
                    style = typography.caption1,
                    color = if (isSelected) colors.systemBlue else colors.secondaryLabel,
                )
            }
        }
    }
}
