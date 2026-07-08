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
 *
 * IMPL-16：[isQuotaExhausted] / [usedToday] 接受可选 [zone] 参数，
 * 调用方应传入账号自身时区，避免跨时区旅行时配额边界漂移。
 */
class DailyQuotaChecker(
    private val tweetRepository: TweetRepository,
    private val defaultZone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * 判断账号当日是否已达配额上限。
     *
     * @param accountId 账号 ID。
     * @param level 当前 AI 活跃度档位。
     * @param now 当前时刻；用于确定"当日"边界。
     * @param zone 账号所属时区（IMPL-16），为空时回退到 [defaultZone]。
     * @return true 表示已达上限，应跳过本次推文生成。
     */
    suspend fun isQuotaExhausted(
        accountId: String,
        level: AiActivityLevel,
        now: Instant = Instant.now(),
        zone: ZoneId? = null,
    ): Boolean {
        val startOfDay = startOfDayMillis(now, zone ?: defaultZone)
        val count = tweetRepository.countByAuthorSince(accountId, startOfDay)
        return count >= level.dailyPostsPerAccount
    }

    /**
     * 返回账号当日已发布推文数（便于日志与可观测性）。
     */
    suspend fun usedToday(
        accountId: String,
        now: Instant = Instant.now(),
        zone: ZoneId? = null,
    ): Int {
        val startOfDay = startOfDayMillis(now, zone ?: defaultZone)
        return tweetRepository.countByAuthorSince(accountId, startOfDay)
    }

    private fun startOfDayMillis(now: Instant, zone: ZoneId): Long {
        val zoned = now.atZone(zone)
        val localDate: LocalDate = zoned.toLocalDate()
        return localDate.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
