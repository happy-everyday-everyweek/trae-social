package com.trae.social.core.scheduler.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TimeWindow] 单元测试。
 *
 * 覆盖：合法构造、参数校验、contains 判定、lengthHours 计算。
 */
class TimeWindowTest {

    @Test
    fun `合法构造`() {
        val w = TimeWindow(9, 12)
        assertEquals(9, w.startHour)
        assertEquals(12, w.endHour)
        assertEquals(3, w.lengthHours)
    }

    @Test
    fun `startHour 等于 0 endHour 等于 24 表示全天`() {
        val w = TimeWindow(0, 24)
        assertEquals(24, w.lengthHours)
    }

    @Test
    fun `contains 判定边界`() {
        val w = TimeWindow(9, 12)
        assertFalse(w.contains(8))
        assertTrue(w.contains(9))
        assertTrue(w.contains(10))
        assertTrue(w.contains(11))
        assertFalse(w.contains(12))
    }

    @Test
    fun `startHour 超出范围时抛出异常`() {
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(-1, 5) }
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(24, 25) }
    }

    @Test
    fun `endHour 超出范围时抛出异常`() {
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(0, 25) }
    }

    @Test
    fun `endHour 不大于 startHour 时抛出异常`() {
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(12, 12) }
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(12, 9) }
    }
}
