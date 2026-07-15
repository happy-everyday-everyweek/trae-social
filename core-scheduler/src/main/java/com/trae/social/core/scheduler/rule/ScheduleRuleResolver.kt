package com.trae.social.core.scheduler.rule

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * 时间窗解析与触发时刻计算工具。
 *
 * 24 槽 bool 数组的语义：索引 i 对应 [i:00, (i+1):00) 这一小时是否为活跃窗。
 * 连续的 true 槽位会被合并为一个 [TimeWindow]；跨夜（如 22-23-0-1）不合并为单窗，
 * 而是按自然日切分，便于在 [missedWindows] 中按"昨日/今日"语义识别。
 */
object ScheduleRuleResolver {

    /**
     * 将 24 槽 bool 数组解析为按小时排序的连续活跃时间段列表。
     *
     * 例：[false]*9 + [true]*3 + [false]*4 + [true]*2 + [false]*6
     * 解析为 [TimeWindow(9,12), TimeWindow(16,18)]。
     *
     * 空数组或全 false 时返回空列表。
     *
     * @param activeWindows 24 槽 bool 数组；长度不足 24 时尾部补 false，超出截断。
     */
    fun parseWindows(activeWindows: List<Boolean>): List<TimeWindow> {
        val windows = activeWindows.take(ScheduleRule.HOURS_PER_DAY).let {
            if (it.size >= ScheduleRule.HOURS_PER_DAY) it
            else it + List(ScheduleRule.HOURS_PER_DAY - it.size) { false }
        }
        val result = mutableListOf<TimeWindow>()
        var start = -1
        for (h in 0 until ScheduleRule.HOURS_PER_DAY) {
            val active = windows[h]
            if (active && start < 0) {
                start = h
            } else if (!active && start >= 0) {
                result.add(TimeWindow(start, h))
                start = -1
            }
        }
        if (start >= 0) {
            // 末尾连续活跃段，endHour=24
            result.add(TimeWindow(start, ScheduleRule.HOURS_PER_DAY))
        }
        return result
    }

    /**
     * 计算下一次触发时刻。
     *
     * 规则：
     * 1. 若 [now] 落在某个活跃窗内，且该窗内已发布的推文数未达 [ScheduleRule.postsPerWindow]，
     *    返回当前窗内的一个随机时刻（不早于 now）。
     * 2. 否则，返回下一个（按时间顺序）活跃窗内的随机时刻。
     * 3. 若今日无后续活跃窗、且今日活跃窗已用尽，则跳到次日首个活跃窗。
     * 4. 全天无活跃窗时返回 null（该账号不会被调度）。
     *
     * P1 修复：新增 [postsInWindowProvider] 参数，调用方传入一个查询函数
     * `(accountId, windowStartMillis, windowEndMillis) -> 已发布推文数`，
     * 用于判断当前窗内推文数是否已达 [ScheduleRule.postsPerWindow] 上限。
     * 若当前窗已满，则跳到下一个窗。若为 null 则不检查窗内上限（向后兼容）。
     *
     * @param rule 调度规则。
     * @param now 当前时刻。
     * @param zone 时区，决定"小时"语义；默认系统时区。
     * @param random 随机源，便于测试。
     * @param postsInWindowProvider 查询窗内已发布推文数的函数；返回值 >= rule.postsPerWindow 时视为窗已满。
     * @return 下一次触发时刻；rule 无活跃窗时返回 null。
     */
    suspend fun nextTriggerTime(
        rule: ScheduleRule,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        random: Random = Random.Default,
        postsInWindowProvider: (suspend (accountId: String, windowStartMillis: Long, windowEndMillis: Long) -> Int)? = null,
    ): Instant? {
        val windows = parseWindows(rule.activeWindows)
        if (windows.isEmpty()) return null

        val zonedNow = ZonedDateTime.ofInstant(now, zone)
        val today = zonedNow.toLocalDate()
        val currentHour = zonedNow.hour

        // 1. 今日当前活跃窗：若仍在窗口结束前，且窗内推文数未达上限，则在窗内取随机时刻
        val currentWindow = windows.firstOrNull { it.contains(currentHour) }
        if (currentWindow != null) {
            // P1 修复：检查窗内已发布数是否已达 postsPerWindow 上限
            val windowFull = if (postsInWindowProvider != null && rule.postsPerWindow > 0) {
                val windowStart = windowStartMillis(currentWindow, today, zone)
                val windowEnd = atWindowEndInstant(currentWindow, today, zone).toEpochMilli()
                val published = runCatching {
                    postsInWindowProvider(rule.accountId, windowStart, windowEnd)
                }.getOrDefault(0)
                published >= rule.postsPerWindow
            } else {
                false
            }

            if (!windowFull) {
                val trigger = randomMomentInWindow(currentWindow, today, zone, random)
                if (!trigger.isBefore(now)) return trigger
                // 当前窗内随机时刻已过：取当前窗剩余时段的随机点（不早于 now + 1s）
                val remainingEarliest = zonedNow.plusSeconds(1).toInstant()
                val windowEnd = atWindowEndInstant(currentWindow, today, zone)
                if (remainingEarliest.isBefore(windowEnd)) {
                    return randomInstantBetween(remainingEarliest, windowEnd, random)
                }
                // #86：即使剩余 < 1min，也尽量用完当前窗配额
                return windowEnd.minusSeconds(1)
            }
            // 当前窗已满或已接近结束，落入下方"今日后续窗"分支
        }

        // 2. 今日后续活跃窗（startHour 严格大于当前小时）
        val futureToday = windows.firstOrNull { it.startHour > currentHour }
        if (futureToday != null) {
            return randomMomentInWindow(futureToday, today, zone, random)
        }

        // 3. 次日首个活跃窗
        val tomorrow = today.plusDays(1)
        return randomMomentInWindow(windows.first(), tomorrow, zone, random)
    }

    /**
     * 识别 [lastRun] 至 [now] 之间错过的活跃窗（补发用）。
     *
     * 错过定义：该活跃窗的整窗区间落在 [lastRun, now] 之内，且未被处理过。
     * 每个被识别的窗返回一个 [MissedWindow]（含日期，IMPL-4）；调用方按需为每窗补发一条推文。
     *
     * 注意：本函数仅返回窗口信息，不查库判断是否已发推。
     *
     * @param rule 调度规则。
     * @param lastRun 上次执行时刻；为 null 或早于昨日起算点时按"昨日 00:00"处理。
     * @param now 当前时刻。
     * @param zone 时区。
     * @return 错过的活跃窗列表（按时间升序，含日期）。
     */
    fun missedWindows(
        rule: ScheduleRule,
        lastRun: Instant?,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MissedWindow> {
        val windows = parseWindows(rule.activeWindows)
        if (windows.isEmpty()) return emptyList()

        val zonedNow = ZonedDateTime.ofInstant(now, zone)
        val zonedLast = if (lastRun != null) {
            ZonedDateTime.ofInstant(lastRun, zone)
        } else {
            zonedNow.minusDays(1).toLocalDate().atStartOfDay(zone)
        }

        if (!zonedLast.isBefore(zonedNow)) return emptyList()

        val result = mutableListOf<MissedWindow>()
        var day = zonedLast.toLocalDate()
        val endDay = zonedNow.toLocalDate()

        while (!day.isAfter(endDay)) {
            for (w in windows) {
                val windowStart = ZonedDateTime.of(day, java.time.LocalTime.of(w.startHour, 0), zone)
                val windowEnd = ZonedDateTime.of(day, java.time.LocalTime.of(w.endHour.coerceAtMost(23), 0), zone)
                    .let {
                        if (w.endHour == 24) it.plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                        else it
                    }
                // #111：DST 春跳时间间隙内 ZonedDateTime.of 可能将时刻向后推移，
                // 导致 start == end（零长度窗口），失去随机性。跳过此类窗口。
                if (!windowEnd.isAfter(windowStart)) continue
                // 窗口必须在 [lastRun, now] 区间内完整或部分错过：windowEnd <= now 且 windowStart >= lastRun
                // 补发策略：只补完整错过的窗（窗口已结束、且开始时间晚于 lastRun）
                if (!windowEnd.isAfter(zonedNow) && !windowStart.isBefore(zonedLast)) {
                    // 去重：今日当前小时所在的活跃窗若尚未结束，不视为"错过"
                    if (day == endDay && w.contains(zonedNow.hour)) continue
                    result.add(MissedWindow(date = day, window = w))
                }
            }
            day = day.plusDays(1)
        }
        return result
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private fun randomMomentInWindow(
        window: TimeWindow,
        date: LocalDate,
        zone: ZoneId,
        random: Random,
    ): Instant {
        val start = ZonedDateTime.of(date, java.time.LocalTime.of(window.startHour, 0), zone).toInstant()
        val end = atWindowEndInstant(window, date, zone)
        return randomInstantBetween(start, end, random)
    }

    private fun atWindowEndInstant(window: TimeWindow, date: LocalDate, zone: ZoneId): Instant {
        return if (window.endHour == 24) {
            ZonedDateTime.of(date.plusDays(1), java.time.LocalTime.MIDNIGHT, zone).toInstant()
        } else {
            ZonedDateTime.of(date, java.time.LocalTime.of(window.endHour, 0), zone).toInstant()
        }
    }

    /**
     * 计算活跃窗起始时刻（毫秒）。P1 修复：供 postsInWindowProvider 查询使用。
     */
    private fun windowStartMillis(window: TimeWindow, date: LocalDate, zone: ZoneId): Long {
        return ZonedDateTime.of(date, java.time.LocalTime.of(window.startHour, 0), zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * #109：根据触发时刻反推其所属活跃窗的起始时刻。
     *
     * 用于自链路径生成 dedup key：触发时刻是窗内随机点，
     * 但 dedup key 需要窗口起始时刻以保证跨重启幂等。
     *
     * M3 修复：传入 [activeWindows] 以查找触发时刻实际所属的活跃窗，
     * 返回窗口真正的 startHour:00（而非触发时刻的 hour:00）。
     * 对 9-12 多小时窗口、触发于 10:30 的情况，正确返回 9:00 而非 10:00，
     * 与补发路径（用 missedWindow.startHour）的 dedup key 一致。
     *
     * @param trigger 触发时刻（窗内随机点）
     * @param zone 时区
     * @param activeWindows 24 槽 bool 数组，用于解析活跃窗
     * @return 窗口起始时刻；无法确定时返回 null
     */
    fun windowStartForTrigger(
        trigger: Instant,
        zone: ZoneId,
        activeWindows: List<Boolean> = emptyList(),
    ): Instant? {
        val zoned = ZonedDateTime.ofInstant(trigger, zone)
        val hour = zoned.hour
        // M3 修复：从 activeWindows 解析活跃窗，找到包含当前小时的窗口
        val startHour = if (activeWindows.isNotEmpty()) {
            val windows = parseWindows(activeWindows)
            val containingWindow = windows.firstOrNull { it.contains(hour) }
            containingWindow?.startHour ?: hour
        } else {
            hour
        }
        return ZonedDateTime.of(zoned.toLocalDate(), java.time.LocalTime.of(startHour, 0), zone)
            .toInstant()
    }

    /**
     * M3 修复：根据窗口起始时刻与活跃窗配置，计算窗口实际结束时刻（毫秒）。
     *
     * 用于 TweetGenerationWorker 的 TOCTOU 检查：此前硬编码 windowEnd = windowStart + 1h，
     * 对多小时窗口只统计了首小时推文，导致 postsPerWindow 可被突破。
     *
     * @param windowStartMillis 窗口起始时刻（毫秒）
     * @param zone 时区
     * @param activeWindows 24 槽 bool 数组
     * @return 窗口结束时刻（毫秒）；无法确定时回退 windowStartMillis + 1h
     */
    fun windowEndMillisForStart(
        windowStartMillis: Long,
        zone: ZoneId,
        activeWindows: List<Boolean>,
    ): Long {
        if (activeWindows.isEmpty()) return windowStartMillis + 3600_000L
        val zoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(windowStartMillis), zone)
        val windows = parseWindows(activeWindows)
        val matchingWindow = windows.firstOrNull { it.contains(zoned.hour) }
            ?: return windowStartMillis + 3600_000L
        return atWindowEndInstant(matchingWindow, zoned.toLocalDate(), zone).toEpochMilli()
    }

    private fun randomInstantBetween(start: Instant, end: Instant, random: Random): Instant {
        val s = start.toEpochMilli()
        val e = end.toEpochMilli()
        if (e <= s) return start
        val offset = random.nextLong(0, e - s)
        return Instant.ofEpochMilli(s + offset)
    }
}
