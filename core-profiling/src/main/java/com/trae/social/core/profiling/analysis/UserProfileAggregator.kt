package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.dao.ScenarioEffectStats
import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.dao.UserProfileOverrideDao
import com.trae.social.core.data.model.EventSummary
import com.trae.social.core.data.model.FeedbackEffect
import com.trae.social.core.data.model.FeedbackMessageSummary
import com.trae.social.core.data.model.UserActionType
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
        // 第六轮 review B1 修复：A/B 反哺闭环的 delta 计算改为按事件类型分流：
        //   - INTERACTION_SCHEDULED：调度器打标的曝光事件（driven/control 各算一次曝光配额），
        //     仅累计 driven/control 计数，不算互动。
        //   - SCENARIO_OUTCOME：用户对先前打标目标产生真实互动（like/comment/retweet/bookmark）
        //     时由 FeedViewModel 发出，extra 继承 INTERACTION_SCHEDULED 的 scenarioId/drivenByProfile，
        //     累计 drivenInteract/controlInteract。
        // drivenRate = drivenInteract / driven（driven 组互动率），
        // controlRate = controlInteract / control（control 组互动率），
        // delta = drivenRate - controlRate。delta < 0 时下一轮降低该场景 feedbackWeights。
        //
        // 旧实现用 INTERACTION_TYPES 集合（{TWEET_LIKE, TWEET_COMMENT, TWEET_RETWEET, TWEET_BOOKMARK}）
        // 判断是否互动，但调度器打标事件本身用了 TWEET_LIKE/TWEET_COMMENT/PUBLISH_TWEET 类型，
        // 导致每条打标都被算作"interaction"→ drivenRate=controlRate=1.0 → delta=0 恒为 0，
        // A/B 闭环失效，LLM 永远不会被降低某场景权重。新实现通过专用事件类型彻底解耦
        // "调度器曝光打标"与"用户真实互动归因"。
        for (e in domains) {
            val isDriven = ProfileMappers.readExtraBoolean(e.extra, "drivenByProfile")
            when (e.type) {
                UserActionType.INTERACTION_SCHEDULED -> {
                    if (isDriven) driven++ else control++
                }
                UserActionType.SCENARIO_OUTCOME -> {
                    if (isDriven) drivenInteract++ else controlInteract++
                }
                else -> {
                    // 兼容旧数据：历史打标事件可能仍是 TWEET_LIKE/PUBLISH_TWEET 等类型
                    //（迁移前已落库的事件），按旧逻辑计入 driven/control+互动统计。
                    // 新数据应全部走 INTERACTION_SCHEDULED + SCENARIO_OUTCOME 路径。
                    if (e.type.name in LEGACY_INTERACTION_TYPES) {
                        if (isDriven) {
                            driven++
                            drivenInteract++
                        } else {
                            control++
                            controlInteract++
                        }
                    }
                }
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
        // B1 修复后保留的旧互动类型集合，仅用于兼容迁移前已落库的打标事件（那时打标用
        // TWEET_LIKE/TWEET_COMMENT/PUBLISH_TWEET/FEEDBACK_OVERRIDE_APPLIED 等真实类型）。
        // 新数据应通过 INTERACTION_SCHEDULED（曝光）+ SCENARIO_OUTCOME（互动归因）路径。
        val LEGACY_INTERACTION_TYPES = setOf(
            "TWEET_LIKE", "TWEET_COMMENT", "TWEET_RETWEET", "TWEET_BOOKMARK",
            "PUBLISH_TWEET", "FEEDBACK_OVERRIDE_APPLIED",
        )
    }
}
