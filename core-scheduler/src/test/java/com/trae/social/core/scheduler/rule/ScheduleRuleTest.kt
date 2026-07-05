package com.trae.social.core.scheduler.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [ScheduleRule] 单元测试。
 *
 * 覆盖：归一化窗口数组、postsPerWindow 校验。
 */
class ScheduleRuleTest {

    @Test
    fun `normalizedWindows 不足 24 时尾部补 false`() {
        val rule = ScheduleRule(
            accountId = "acc1",
            activeWindows = listOf(true, true, false),
            postsPerWindow = 1,
        )
        val normalized = rule.normalizedWindows
        assertEquals(24, normalized.size)
        assertEquals(true, normalized[0])
        assertEquals(true, normalized[1])
        assertEquals(false, normalized[2])
        assertEquals(false, normalized[23])
    }

    @Test
    fun `normalizedWindows 超过 24 时截断`() {
        val rule = ScheduleRule(
            accountId = "acc1",
            activeWindows = List(30) { true },
            postsPerWindow = 1,
        )
        val normalized = rule.normalizedWindows
        assertEquals(24, normalized.size)
    }

    @Test
    fun `normalizedWindows 长度恰好 24 时原样返回`() {
        val windows = List(24) { it % 2 == 0 }
        val rule = ScheduleRule("acc1", windows, postsPerWindow = 2)
        assertEquals(windows, rule.normalizedWindows)
    }

    @Test
    fun `postsPerWindow 为负数时抛出异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleRule("acc1", emptyList(), postsPerWindow = -1)
        }
    }

    @Test
    fun `postsPerWindow 等于 0 合法`() {
        val rule = ScheduleRule("acc1", emptyList(), postsPerWindow = 0)
        assertEquals(0, rule.postsPerWindow)
    }
}
