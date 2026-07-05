package com.trae.social.core.scheduler.rule

/**
 * 调度去重键生成工具。
 *
 * 同一账号在同一活跃窗内的同一次排程，应产生相同的 deduplicationKey，
 * 用于 [com.trae.social.core.data.entity.TweetEntity.deduplicationKey] 唯一约束，
 * 防止 Worker 重试或补发导致重复推文（调度幂等）。
 */
object DeduplicationKeys {

    /**
     * 生成推文去重键。
     *
     * @param accountId 账号 ID
     * @param windowStart 活跃窗起始时刻（epoch millis），同一窗内多次调用应传同一值
     * @param sequenceNo 窗内序号（同一窗内不同条推文从 0 开始递增）
     * @return 形如 "accountId_windowStart_sequenceNo" 的字符串
     */
    fun forTweet(accountId: String, windowStart: Long, sequenceNo: Int): String {
        require(accountId.isNotBlank()) { "accountId must not be blank" }
        require(sequenceNo >= 0) { "sequenceNo must be >= 0, but was $sequenceNo" }
        return "${accountId}_${windowStart}_$sequenceNo"
    }
}
