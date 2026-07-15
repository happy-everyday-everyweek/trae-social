package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.dao.ScenarioEffectStats
import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.dao.UserProfileOverrideDao
import com.trae.social.core.data.model.EventSummary
import com.trae.social.core.data.model.FeedbackEffect
import com.trae.social.core.data.model.FeedbackMessageSummary
import com.trae.social.core.data.model.UserFeedbackSummary
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.profiling.capture.ProfilingGate
import com.trae.social.core.profiling.mapping.ProfileMappers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM 画像输入摘要聚合器（#146 第三层）。
 *
 * 分层聚合（主题层 / 时间层 / 行为层 / 反哺效果层 / 用户反馈层），
 * 总输入预算 <=2K tokens，超出按优先级裁剪（用户反馈层 > 反哺效果层 > 行为层 > 主题层 > 时间层）。
 */
@Singleton
class UserProfileAggregator @Inject constructor(
    private val userActionDao: UserActionDao,
    private val userProfileDao: UserProfileDao,
    private val overrideDao: UserProfileOverrideDao,
    private val feedbackDao: com.trae.social.core.data.dao.UserProfileFeedbackDao,
    private val configRepository: ConfigRepository,
    private val gate: ProfilingGate,
) {

    /**
     * 聚合 LLM 画像所需输入摘要。
     *
     * @param snapshot 最新基础分析快照。
     * @param since 反哺效果统计起始时间。
     */
    suspend fun aggregate(snapshot: UserProfileSnapshot, since: Long): AggregatedInput {
        val totalEvents = userActionDao.countSince(since)
        val eventSummary = EventSummary(
            totalEvents = totalEvents,
            topThemes = snapshot.evidence.topThemes,
            topActiveHours = snapshot.evidence.topActiveHours,
            interactionRates = snapshot.interactionTendency,
            postingCadenceSummary = "频率${"%.2f".format(snapshot.postingCadence.postFrequency)}/日" +
                " 均文${snapshot.postingCadence.avgCaptionLength}字",
            periodicitySummary = if (snapshot.periodicity.isWeekendDominant) "周末活跃型" else "工作日活跃型",
        )

        val overrides = overrideDao.active().mapNotNull { it.toDomain() }
        val recentFeedback = feedbackDao.recent(FEEDBACK_LIMIT).map {
            FeedbackMessageSummary(role = it.role, content = it.content, createdAt = it.createdAt)
        }
        val userFeedback = UserFeedbackSummary(recentMessages = recentFeedback, activeOverrides = overrides)

        val feedbackEffect = computeFeedbackEffect(since)

        return AggregatedInput(
            snapshot = snapshot,
            eventSummary = eventSummary,
            feedbackEffect = feedbackEffect,
            userFeedback = userFeedback,
            previousNarrative = userProfileDao.latestVersion()?.narrative,
        )
    }

    /**
     * 计算各场景 A/B 反哺效果（driven/control 差异）。
     *
     * delta < 0 时下一轮降低该场景 feedbackWeights（负反馈修正）。
     */
    private suspend fun computeFeedbackEffect(since: Long): FeedbackEffect {
        if (!gate.isEnabled()) return FeedbackEffect(emptyMap(), emptyList())
        val deltas = mutableMapOf<Int, Double>()
        for (scenarioId in 1..8) {
            runCatching {
                val pattern = "%\"scenarioId\":$scenarioId%"
                val events = userActionDao.queryScenarioEventsSince(since, pattern)
                if (events.isEmpty()) return@runCatching
                val stats = computeScenarioStats(scenarioId, events)
                deltas[scenarioId] = stats.delta
            }.onFailure { /* 单场景失败不影响其他 */ }
        }
        val negative = deltas.filterValues { it < 0 }.keys.toList()
        return FeedbackEffect(deltas, negative)
    }

    private fun computeScenarioStats(scenarioId: Int, events: List<com.trae.social.core.data.entity.UserActionEventEntity>): ScenarioEffectStats {
        val domains = events.mapNotNull { ProfileMappers.run { it.toDomain() } }
        var driven = 0
        var control = 0
        var drivenInteract = 0
        var controlInteract = 0
        for (e in domains) {
            val isDriven = ProfileMappers.readExtraBoolean(e.extra, "drivenByProfile")
            val isInteraction = e.type.name in INTERACTION_TYPES
            if (isDriven) {
                driven++
                if (isInteraction) drivenInteract++
            } else {
                control++
                if (isInteraction) controlInteract++
            }
        }
        val drivenRate = if (driven == 0) 0.0 else drivenInteract.toDouble() / driven
        val controlRate = if (control == 0) 0.0 else controlInteract.toDouble() / control
        return ScenarioEffectStats(scenarioId, driven, control, drivenRate, controlRate, drivenRate - controlRate)
    }

    private fun com.trae.social.core.data.entity.UserActionEventEntity.toDomain() =
        ProfileMappers.run { toDomain() }

    private fun com.trae.social.core.data.entity.UserProfileOverrideEntity.toDomain() =
        ProfileMappers.run { toDomain() }

    /** 聚合结果。 */
    data class AggregatedInput(
        val snapshot: UserProfileSnapshot,
        val eventSummary: EventSummary,
        val feedbackEffect: FeedbackEffect,
        val userFeedback: UserFeedbackSummary,
        val previousNarrative: String?,
    )

    private companion object {
        const val FEEDBACK_LIMIT = 10
        val INTERACTION_TYPES = setOf(
            "TWEET_LIKE", "TWEET_COMMENT", "TWEET_RETWEET", "TWEET_BOOKMARK",
        )
    }
}
