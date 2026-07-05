package com.trae.social.core.scheduler.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [ScheduleRuleResolver.missedWindows] 单元测试。
 *
 * 覆盖：lastRun 至 now 间完整错过的活跃窗识别、今日当前窗不视为错过、空规则。
 */
class ScheduleRuleResolverMissedWindowsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun rule(windows: List<Boolean>) =
        ScheduleRule(accountId = "acc1", activeWindows = windows, postsPerWindow = 1)

    @Test
    fun `无活跃窗时返回空列表`() {
        val r = rule(List(24) { false })
        val lastRun = ZonedDateTime.of(2026, 7, 5, 8, 0, 0, 0, zone).toInstant()
        val now = ZonedDateTime.of(2026, 7, 5, 20, 0, 0, 0, zone).toInstant()
        val missed = ScheduleRuleResolver.missedWindows(r, lastRun, now, zone)
        assertTrue(missed.isEmpty())
    }

    @Test
    fun `识别完整错过的活跃窗`() {
        // 9-11 活跃
        val r = rule(List(24) { h -> h in 9..10 })
        // lastRun = 06:00，now = 12:00 -> 9-11 窗完整错过
        val lastRun = ZonedDateTime.of(2026, 7, 5, 6, 0, 0, 0, zone).toInstant()
        val now = ZonedDateTime.of(2026, 7, 5, 12, 0, 0, 0, zone).toInstant()
        val missed = ScheduleRuleResolver.missedWindows(r, lastRun, now, zone)
        assertEquals(1, missed.size)
        assertEquals(TimeWindow(9, 11), missed[0])
    }

    @Test
    fun `今日当前未结束的活跃窗不视为错过`() {
        // 9-11 活跃
        val r = rule(List(24) { h -> h in 9..10 })
        // lastRun = 06:00，now = 10:00 -> 当前窗仍在进行，不视为错过
        val lastRun = ZonedDateTime.of(2026, 7, 5, 6, 0, 0, 0, zone).toInstant()
        val now = ZonedDateTime.of(2026, 7, 5, 10, 0, 0, 0, zone).toInstant()
        val missed = ScheduleRuleResolver.missedWindows(r, lastRun, now, zone)
        assertTrue("当前未结束窗不应视为错过", missed.isEmpty())
    }

    @Test
    fun `识别多个错过的活跃窗`() {
        // 9-11 与 14-16 活跃
        val r = rule(List(24) { h -> h in 9..10 || h in 14..15 })
        // lastRun = 06:00，now = 17:00 -> 两个窗均完整错过
        val lastRun = ZonedDateTime.of(2026, 7, 5, 6, 0, 0, 0, zone).toInstant()
        val now = ZonedDateTime.of(2026, 7, 5, 17, 0, 0, 0, zone).toInstant()
        val missed = ScheduleRuleResolver.missedWindows(r, lastRun, now, zone)
        assertEquals(2, missed.size)
        assertEquals(TimeWindow(9, 11), missed[0])
        assertEquals(TimeWindow(14, 16), missed[1])
    }

    @Test
    fun `lastRun 为 null 时回退为 24 小时前`() {
        // 9-11 活跃
        val r = rule(List(24) { h -> h in 9..10 })
        // now = 12:00，lastRun = null -> 应识别 9-11 窗为错过
        val now = ZonedDateTime.of(2026, 7, 5, 12, 0, 0, 0, zone).toInstant()
        val missed = ScheduleRuleResolver.missedWindows(r, null, now, zone)
        // 24 小时前为昨日 12:00，昨日 9-11 窗应被识别为错过
        assertTrue("应至少识别 1 个错过窗", missed.isNotEmpty())
        assertEquals(TimeWindow(9, 11), missed.first())
    }

    @Test
    fun `lastRun 不早于 now 时返回空列表`() {
        val r = rule(List(24) { true })
        val now = ZonedDateTime.of(2026, 7, 5, 12, 0, 0, 0, zone).toInstant()
        val missed = ScheduleRuleResolver.missedWindows(r, now, now, zone)
        assertTrue(missed.isEmpty())
    }

    @Test
    fun `跨天识别错过的窗`() {
        // 9-11 活跃
        val r = rule(List(24) { h -> h in 9..10 })
        // lastRun = 昨日 06:00，now = 今日 12:00
        val lastRun = ZonedDateTime.of(2026, 7, 4, 6, 0, 0, 0, zone).toInstant()
        val now = ZonedDateTime.of(2026, 7, 5, 12, 0, 0, 0, zone).toInstant()
        val missed = ScheduleRuleResolver.missedWindows(r, lastRun, now, zone)
        // 应识别昨日 9-11 与今日 9-11 两个窗
        assertEquals(2, missed.size)
        assertEquals(TimeWindow(9, 11), missed[0])
        assertEquals(TimeWindow(9, 11), missed[1])
    }
}
