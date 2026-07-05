package com.trae.social.core.scheduler.rule

/**
 * 单个连续活跃时间段。
 *
 * @param startHour 起始小时（0-23，含），表示该活跃窗从 [startHour]:00 开始。
 * @param endHour 结束小时（1-24，不含），表示该活跃窗在 [endHour]:00 之前结束。
 *                endHour == 24 表示跨夜到次日 00:00；startHour == 0 且 endHour == 24 表示全天活跃。
 */
data class TimeWindow(
    val startHour: Int,
    val endHour: Int,
) {
    init {
        require(startHour in 0..23) { "startHour must be in 0..23, but was $startHour" }
        require(endHour in 1..24) { "endHour must be in 1..24, but was $endHour" }
        require(endHour > startHour) { "endHour($endHour) must be > startHour($startHour)" }
    }

    /**
     * 该时间段的小时跨度。
     */
    val lengthHours: Int get() = endHour - startHour

    /**
     * 判断指定小时（0-23）是否落在该活跃窗内。
     */
    fun contains(hour: Int): Boolean = hour in startHour until endHour
}
