package com.trae.social.core.scheduler.rule

import java.time.LocalDate

/**
 * 带日期的活跃窗口（IMPL-4）。
 *
 * [TimeWindow] 仅含 startHour/endHour，无法区分"昨日 9-12"与"今日 9-12"。
 * 本类补充 [date] 字段，使调用方能计算正确的 windowStartMillis 与 deduplicationKey，
 * 避免跨日补发时 key 冲突。
 *
 * @param date 窗口所属的自然日期
 * @param window 时间段（startHour/endHour）
 */
data class MissedWindow(
    val date: LocalDate,
    val window: TimeWindow,
) {
    val startHour: Int get() = window.startHour
    val endHour: Int get() = window.endHour
}
