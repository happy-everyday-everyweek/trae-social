package com.trae.social.core.scheduler.rule

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * [ScheduleRuleResolver.nextTriggerTime] 单元测试。
 *
 * 覆盖：当前窗内触发、今日后续窗、次日首窗、无活跃窗返回 null。
 *
 * nextTriggerTime 为 suspend 函数，各测试用 runBlocking 驱动。
 */
class ScheduleRuleResolverNextTriggerTimeTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun rule(windows: List<Boolean>, postsPerWindow: Int = 1) =
        ScheduleRule(accountId = "acc1", activeWindows = windows, postsPerWindow = postsPerWindow)

    @Test
    fun `无活跃窗时返回 null`() = runBlocking {
        val r = rule(List(24) { false })
        val now = ZonedDateTime.of(2026, 7, 5, 10, 0, 0, 0, zone).toInstant()
        val result = ScheduleRuleResolver.nextTriggerTime(r, now, zone, Random(0))
        assertNull(result)
    }

    @Test
    fun `当前在活跃窗内时触发时刻落在窗内`() = runBlocking {
        // 9-12 活跃
        val r = rule(List(24) { h -> h in 9..11 })
        // now = 10:00
        val now = ZonedDateTime.of(2026, 7, 5, 10, 0, 0, 0, zone).toInstant()
        val result = ScheduleRuleResolver.nextTriggerTime(r, now, zone, Random(42))
        assertNotNull(result)
        val windowStart = ZonedDateTime.of(2026, 7, 5, 9, 0, 0, 0, zone).toInstant()
        val windowEnd = ZonedDateTime.of(2026, 7, 5, 12, 0, 0, 0, zone).toInstant()
        assertTrue("触发时刻应在窗内 [9:00, 12:00)", !result!!.isBefore(windowStart))
        assertTrue(result.isBefore(windowEnd))
    }

    @Test
    fun `当前在活跃窗内且随机时刻已过时仍返回窗内未来时刻`() = runBlocking {
        // 0-23 全天活跃
        val r = rule(List(24) { true })
        // now = 10:00
        val now = ZonedDateTime.of(2026, 7, 5, 10, 0, 0, 0, zone).toInstant()
        val result = ScheduleRuleResolver.nextTriggerTime(r, now, zone, Random(0))
        assertNotNull(result)
        // 触发时刻应不早于 now（随机生成时刻若已过则取剩余时段）
        assertTrue("触发时刻应不早于 now", !result!!.isBefore(now))
    }

    @Test
    fun `当前不在活跃窗且今日有后续窗时返回今日后续窗内时刻`() = runBlocking {
        // 14-16 活跃
        val r = rule(List(24) { h -> h in 14..15 })
        // now = 10:00（上午，下午才有活跃窗）
        val now = ZonedDateTime.of(2026, 7, 5, 10, 0, 0, 0, zone).toInstant()
        val result = ScheduleRuleResolver.nextTriggerTime(r, now, zone, Random(0))
        assertNotNull(result)
        val windowStart = ZonedDateTime.of(2026, 7, 5, 14, 0, 0, 0, zone).toInstant()
        val windowEnd = ZonedDateTime.of(2026, 7, 5, 16, 0, 0, 0, zone).toInstant()
        assertTrue("应在今日 14-16 窗内", !result!!.isBefore(windowStart))
        assertTrue(result.isBefore(windowEnd))
    }

    @Test
    fun `今日活跃窗已全部结束时返回次日首窗内时刻`() = runBlocking {
        // 9-11 活跃（上午窗）
        val r = rule(List(24) { h -> h in 9..10 })
        // now = 15:00（下午，今日窗已结束）
        val now = ZonedDateTime.of(2026, 7, 5, 15, 0, 0, 0, zone).toInstant()
        val result = ScheduleRuleResolver.nextTriggerTime(r, now, zone, Random(0))
        assertNotNull(result)
        val nextWindowStart = ZonedDateTime.of(2026, 7, 6, 9, 0, 0, 0, zone).toInstant()
        val nextWindowEnd = ZonedDateTime.of(2026, 7, 6, 11, 0, 0, 0, zone).toInstant()
        assertTrue("应在次日 9-11 窗内", !result!!.isBefore(nextWindowStart))
        assertTrue(result.isBefore(nextWindowEnd))
    }

    @Test
    fun `全天活跃时触发时刻在今日剩余时段内`() = runBlocking {
        val r = rule(List(24) { true })
        val now = ZonedDateTime.of(2026, 7, 5, 23, 30, 0, 0, zone).toInstant()
        val result = ScheduleRuleResolver.nextTriggerTime(r, now, zone, Random(0))
        assertNotNull(result)
        assertTrue("触发时刻应不早于 now", !result!!.isBefore(now))
    }
}
