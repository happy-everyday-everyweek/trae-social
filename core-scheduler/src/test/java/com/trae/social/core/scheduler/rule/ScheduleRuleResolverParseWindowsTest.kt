package com.trae.social.core.scheduler.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScheduleRuleResolver.parseWindows] 单元测试。
 *
 * 覆盖：连续段合并、跨夜段、全 false、长度不足/超出归一化。
 */
class ScheduleRuleResolverParseWindowsTest {

    @Test
    fun `全 false 数组返回空列表`() {
        val windows = ScheduleRuleResolver.parseWindows(List(24) { false })
        assertTrue("全 false 应返回空列表", windows.isEmpty())
    }

    @Test
    fun `空数组返回空列表`() {
        val windows = ScheduleRuleResolver.parseWindows(emptyList())
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `单个连续活跃段被正确解析`() {
        // 9-12 点活跃
        val flags = List(24) { h -> h in 9..11 }
        val windows = ScheduleRuleResolver.parseWindows(flags)
        assertEquals(1, windows.size)
        assertEquals(9, windows[0].startHour)
        assertEquals(12, windows[0].endHour)
    }

    @Test
    fun `两个独立活跃段被分别解析`() {
        // 9-12 与 16-18 活跃
        val flags = List(24) { h -> h in 9..11 || h in 16..17 }
        val windows = ScheduleRuleResolver.parseWindows(flags)
        assertEquals(2, windows.size)
        assertEquals(TimeWindow(9, 12), windows[0])
        assertEquals(TimeWindow(16, 18), windows[1])
    }

    @Test
    fun `末尾连续段 endHour 为 24`() {
        // 22-23 活跃（末尾段，应延伸到 24）
        val flags = List(24) { h -> h in 22..23 }
        val windows = ScheduleRuleResolver.parseWindows(flags)
        assertEquals(1, windows.size)
        assertEquals(22, windows[0].startHour)
        assertEquals(24, windows[0].endHour)
    }

    @Test
    fun `全天活跃返回单个 0-24 窗口`() {
        val flags = List(24) { true }
        val windows = ScheduleRuleResolver.parseWindows(flags)
        assertEquals(1, windows.size)
        assertEquals(0, windows[0].startHour)
        assertEquals(24, windows[0].endHour)
    }

    @Test
    fun `长度不足 24 时尾部补 false`() {
        // 仅前 3 个为 true，长度 5
        val flags = listOf(true, true, true, false, false)
        val windows = ScheduleRuleResolver.parseWindows(flags)
        assertEquals(1, windows.size)
        assertEquals(0, windows[0].startHour)
        assertEquals(3, windows[0].endHour)
    }

    @Test
    fun `长度超过 24 时截断`() {
        // 前 24 中 0-2 为 true，额外多 5 个 true 应被忽略
        val flags = List(29) { h -> h < 3 }
        val windows = ScheduleRuleResolver.parseWindows(flags)
        assertEquals(1, windows.size)
        assertEquals(0, windows[0].startHour)
        assertEquals(3, windows[0].endHour)
    }

    @Test
    fun `开头活跃段被正确解析`() {
        // 0-2 点活跃
        val flags = List(24) { h -> h in 0..1 }
        val windows = ScheduleRuleResolver.parseWindows(flags)
        assertEquals(1, windows.size)
        assertEquals(0, windows[0].startHour)
        assertEquals(2, windows[0].endHour)
    }
}
