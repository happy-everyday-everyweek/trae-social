package com.trae.social.core.scheduler.ratelimit

import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.repository.TweetRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 单账号每日推文配额检查器（RISK-1：配额超限防护）。
 *
 * 查询 [TweetRepository.countByAuthorSince] 获取账号当日已发布推文数，
 * 与 [AiActivityLevel.dailyPostsPerAccount] 比较。
 */
class DailyQuotaChecker(
    private val tweetRepository: TweetRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * 判断账号当日是否已达配额上限。
     *
     * @param accountId 账号 ID。
     * @param level 当前 AI 活跃度档位。
     * @param now 当前时刻；用于确定"当日"边界。
     * @return true 表示已达上限，应跳过本次推文生成。
     */
    suspend fun isQuotaExhausted(
        accountId: String,
        level: AiActivityLevel,
        now: Instant = Instant.now(),
    ): Boolean {
        val startOfDay = startOfDayMillis(now)
        val count = tweetRepository.countByAuthorSince(accountId, startOfDay)
        return count >= level.dailyPostsPerAccount
    }

    /**
     * 返回账号当日已发布推文数（便于日志与可观测性）。
     */
    suspend fun usedToday(
        accountId: String,
        now: Instant = Instant.now(),
    ): Int {
        val startOfDay = startOfDayMillis(now)
        return tweetRepository.countByAuthorSince(accountId, startOfDay)
    }

    private fun startOfDayMillis(now: Instant): Long {
        val zoned = now.atZone(zone)
        val localDate: LocalDate = zoned.toLocalDate()
        return localDate.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
