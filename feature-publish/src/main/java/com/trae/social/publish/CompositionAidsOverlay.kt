package com.trae.social.publish

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 构图辅助覆盖层：九宫格网格线 + 水平仪 + 点击对焦框。
 *
 * - 网格线：将可见预览区域三等分，绘制 2 横 2 竖半透明白线（三分法）；
 * - 水平仪：中心水平指示线，随设备倾斜反向旋转，水平时变绿；
 * - 对焦框：点击位置短暂显示方框并淡出。
 */
@Composable
internal fun CompositionAidsOverlay(
    showGrid: Boolean,
    rollDegrees: Float,
    hasSensorData: Boolean,
    focusOffset: Offset?,
    focusAlpha: Float,
    ratio: CaptureRatio,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val canvasW = size.width
        val canvasH = size.height
        // 可见预览区域：1:1 时为中心正方形，其余为整块画布
        val region = if (ratio == CaptureRatio.SQUARE) {
            val side = canvasW
            val top = (canvasH - side) / 2f
            Rect(0f, top, canvasW, top + side)
        } else {
            Rect(0f, 0f, canvasW, canvasH)
        }

        // 九宫格构图线（三分法）
        if (showGrid) {
            val gridColor = Color.White.copy(alpha = 0.4f)
            val strokeW = 1.dp.toPx()
            val thirdW = region.width / 3f
            val thirdH = region.height / 3f
            drawLine(
                gridColor,
                start = Offset(region.left + thirdW, region.top),
                end = Offset(region.left + thirdW, region.bottom),
                strokeWidth = strokeW,
            )
            drawLine(
                gridColor,
                start = Offset(region.left + 2 * thirdW, region.top),
                end = Offset(region.left + 2 * thirdW, region.bottom),
                strokeWidth = strokeW,
            )
            drawLine(
                gridColor,
                start = Offset(region.left, region.top + thirdH),
                end = Offset(region.right, region.top + thirdH),
                strokeWidth = strokeW,
            )
            drawLine(
                gridColor,
                start = Offset(region.left, region.top + 2 * thirdH),
                end = Offset(region.right, region.top + 2 * thirdH),
                strokeWidth = strokeW,
            )
        }

        // 水平仪：中心水平线，反向旋转以指示真实水平方向，水平时变绿
        // 无传感器数据（如模拟器）时不绘制，避免恒为 0 度导致的假"已水平"提示
        if (hasSensorData) {
            val isLevel = abs(rollDegrees) < LEVEL_THRESHOLD_DEG
            val levelColor = if (isLevel) Color.Green.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.7f)
            val center = region.center
            val halfLen = 40.dp.toPx()
            rotate(degrees = -rollDegrees, pivot = center) {
                drawLine(
                    color = levelColor,
                    start = Offset(center.x - halfLen, center.y),
                    end = Offset(center.x + halfLen, center.y),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }

        // 点击对焦框：在点击位置绘制淡入淡出方框
        val fo = focusOffset
        if (fo != null && focusAlpha > 0f) {
            val ringSize = 80.dp.toPx()
            drawRect(
                color = Color.White.copy(alpha = focusAlpha),
                topLeft = Offset(fo.x - ringSize / 2f, fo.y - ringSize / 2f),
                size = Size(ringSize, ringSize),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/**
 * 水平仪判定阈值（度）：|roll| 小于此值视为水平，指示线变绿。
 */
private const val LEVEL_THRESHOLD_DEG = 2f
