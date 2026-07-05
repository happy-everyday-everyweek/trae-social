package com.trae.social.core.scheduler.rule

/**
 * 单账号调度规则。
 *
 * 持久化在 [com.trae.social.core.data.entity.AccountEntity.activeWindows] 中，
 * 由调度器读取后转换为 [TimeWindow] 列表用于决策下一次触发时刻。
 *
 * @param accountId 关联账号 ID。
 * @param activeWindows 24 槽 bool 数组，索引为小时（0-23），true 表示该小时活跃。
 *                      长度不足 24 时尾部补 false，长度超过 24 时截断。
 * @param postsPerWindow 每个活跃窗内允许发布的推文数量上限。
 */
data class ScheduleRule(
    val accountId: String,
    val activeWindows: List<Boolean>,
    val postsPerWindow: Int,
) {
    init {
        require(postsPerWindow >= 0) { "postsPerWindow must be >= 0, but was $postsPerWindow" }
    }

    /**
     * 归一化为长度恰好 24 的 bool 数组（不足补 false，超出截断）。
     */
    val normalizedWindows: List<Boolean>
        get() = activeWindows.take(HOURS_PER_DAY).let {
            if (it.size >= HOURS_PER_DAY) it
            else it + List(HOURS_PER_DAY - it.size) { false }
        }

    companion object {
        const val HOURS_PER_DAY: Int = 24
    }
}
