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
    fun `动画缩放为零时无论可访问性开关都返回 true`() {
        // 分支 1：scale <= 0 直接 true，无视 a11y 状态
        assertTrue(computeReduceMotion(scale = 0f, a11yEnabled = false, touchExplorationEnabled = false))
        assertTrue(computeReduceMotion(scale = 0f, a11yEnabled = true, touchExplorationEnabled = false))
        assertTrue(computeReduceMotion(scale = 0f, a11yEnabled = true, touchExplorationEnabled = true))
    }

    @Test
    fun `动画缩放为负数时返回 true`() {
        // 边界：scale 为负数也应返回 true（部分 ROM 在「移除动画」时返回 -1f）
        assertTrue(computeReduceMotion(scale = -1f, a11yEnabled = false, touchExplorationEnabled = false))
    }

    @Test
    fun `缩放为正且可访问性未启用时返回 false`() {
        // 分支 2：scale > 0 且 a11y 未启用 → false
        assertFalse(computeReduceMotion(scale = 1f, a11yEnabled = false, touchExplorationEnabled = false))
    }

    @Test
    fun `缩放为正且触摸浏览关闭时返回 false`() {
        // 分支 3：a11y enabled 但 touchExploration 关闭 → false
        assertFalse(computeReduceMotion(scale = 0.5f, a11yEnabled = true, touchExplorationEnabled = false))
        assertFalse(computeReduceMotion(scale = 1f, a11yEnabled = true, touchExplorationEnabled = false))
    }

    @Test
    fun `缩放为正且可访问性与触摸浏览均启用时返回 true`() {
        // 分支 4：scale > 0 且 a11y enabled 且 touchExploration enabled → true
        assertTrue(computeReduceMotion(scale = 1f, a11yEnabled = true, touchExplorationEnabled = true))
    }

    @Test
    fun `缩放为正的小值视为动画开启`() {
        // scale 为正的小值（如 0.5f）应视为动画开启，仅 a11y 全开才返回 true
        assertFalse(computeReduceMotion(scale = 0.5f, a11yEnabled = false, touchExplorationEnabled = false))
        assertTrue(computeReduceMotion(scale = 0.5f, a11yEnabled = true, touchExplorationEnabled = true))
    }
}
