package com.trae.social.designsystem.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #229：computeReduceMotion 纯函数分支覆盖。
 *
 * 4 个分支：
 * 1. ANIMATOR_DURATION_SCALE <= 0 → true（无视 a11y 状态，含 scale = 0 边界）
 * 2. scale > 0 且 a11y 未启用 → false
 * 3. scale > 0 且 a11y enabled 但 touchExploration 关闭 → false
 * 4. scale > 0 且 a11y enabled 且 touchExploration enabled → true
 */
class ComputeReduceMotionTest {

    @Test
    fun animatorScaleZero_returnsTrue_regardlessOfA11y() {
        // 分支 1：scale <= 0 直接 true，无视 a11y 状态
        assertTrue(computeReduceMotion(scale = 0f, a11yEnabled = false, touchExplorationEnabled = false))
        assertTrue(computeReduceMotion(scale = 0f, a11yEnabled = true, touchExplorationEnabled = false))
        assertTrue(computeReduceMotion(scale = 0f, a11yEnabled = true, touchExplorationEnabled = true))
    }

    @Test
    fun animatorScaleNegative_returnsTrue() {
        // 边界：scale 为负数也应返回 true（部分 ROM 在「移除动画」时返回 -1f）
        assertTrue(computeReduceMotion(scale = -1f, a11yEnabled = false, touchExplorationEnabled = false))
    }

    @Test
    fun scalePositive_a11yDisabled_returnsFalse() {
        // 分支 2：scale > 0 且 a11y 未启用 → false
        assertFalse(computeReduceMotion(scale = 1f, a11yEnabled = false, touchExplorationEnabled = false))
    }

    @Test
    fun scalePositive_touchExplorationDisabled_returnsFalse() {
        // 分支 3：a11y enabled 但 touchExploration 关闭 → false
        assertFalse(computeReduceMotion(scale = 0.5f, a11yEnabled = true, touchExplorationEnabled = false))
        assertFalse(computeReduceMotion(scale = 1f, a11yEnabled = true, touchExplorationEnabled = false))
    }

    @Test
    fun scalePositive_a11yAndTouchExplorationEnabled_returnsTrue() {
        // 分支 4：scale > 0 且 a11y enabled 且 touchExploration enabled → true
        assertTrue(computeReduceMotion(scale = 1f, a11yEnabled = true, touchExplorationEnabled = true))
    }

    @Test
    fun animatorScaleSmallPositive_treatedAsEnabled() {
        // scale 为正的小值（如 0.5f）应视为动画开启，仅 a11y 全开才返回 true
        assertFalse(computeReduceMotion(scale = 0.5f, a11yEnabled = false, touchExplorationEnabled = false))
        assertTrue(computeReduceMotion(scale = 0.5f, a11yEnabled = true, touchExplorationEnabled = true))
    }
}
