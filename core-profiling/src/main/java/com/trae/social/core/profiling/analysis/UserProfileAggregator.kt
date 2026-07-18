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
            // 第七轮 review M1 修复：改用 activeVersion() 替代 latestVersion()。
            // latestVersion 按 createdAt DESC 取最新创建版本，回滚场景下（V5 最新但已回滚到 V2 激活），
            // 下一轮 LLM 拿到 V5 的"坏"叙事 → shouldRollbackNarrative(V5, V6) 因相似度过校验 →
            // V6 落库激活 → 回滚被静默绕过。activeVersion 按 isActive=1 取当前激活版本，避免该问题。
            previousNarrative = userProfileDao.activeVersion()?.narrative,
        )
    }

    /**
     * 计算各场景 A/B 反哺效果（driven/control 差异）。
     *
     * delta < 0 时下一轮降低该场景 feedbackWeights（负反馈修正）。
     *
     * 第七轮 review B1 修复：原实现按 `extra LIKE %"scenarioId":N%` 8 次查询，
     * 无法捕获"真实用户互动事件缺 scenarioId"的情况（场景 1/3/4 真实互动事件不带
     * scenarioId，targetId 命中 AI marker 但 extra 无 scenarioId 字段）。
     * 改为统一查询窗口内全部事件 + 在内存中按 scenarioId 分组 + 按 targetId 隐式关联
     * marker 与 interaction，使所有 8 场景 A/B delta 不再恒为 0。
     */
    private suspend fun computeFeedbackEffect(since: Long): FeedbackEffect {
        if (!gate.isEnabled()) return FeedbackEffect(emptyMap(), emptyList())
        val allEvents = runCatching { userActionDao.queryAllSince(since) }
            .getOrElse { return FeedbackEffect(emptyMap(), emptyList()) }
        if (allEvents.isEmpty()) return FeedbackEffect(emptyMap(), emptyList())

        val deltas = mutableMapOf<Int, Double>()
        for (scenarioId in 1..8) {
            runCatching {
                val stats = computeScenarioStats(scenarioId, allEvents)
                if (stats.driven == 0 && stats.control == 0) return@runCatching
                deltas[scenarioId] = stats.delta
            }.onFailure { /* 单场景失败不影响其他 */ }
        }
        val negative = deltas.filterValues { it < 0 }.keys.toList()
        return FeedbackEffect(deltas, negative)
    }

    /**
     * 计算单场景 A/B 反哺效果统计。
     *
     * 第六轮 review B1 修复：区分"调度器打标事件"与"真实用户互动事件"：
     * - isScenarioMarker=true 的打标事件 = 曝光标记（driven/control 计数），
     *   互动量按 extra.interactionCount 累加（无该字段则按 1 计），代表本次排程曝光的互动规模。
     * - isScenarioMarker 缺省/为 false 且 type 命中 [INTERACTION_TYPES] 的真实用户事件 = 互动计数
     *   （drivenInteract/controlInteract 累加），代表用户在曝光后实际发生的正向互动。
     *
     * 第七轮 review B1 修复：原实现仅识别"显式标记"（event.extra 有 scenarioId=N）的真实互动，
     * 但场景 1/3/4 的真实用户互动事件（如点赞 AI 生成的推文）从不带 scenarioId → drivenInteract/
     * controlInteract 恒为 0 → delta 恒为 0。增加"按 targetId 隐式关联"：真实互动事件若
     * targetId 命中本场景某 marker 的 targetId，则计入本场景互动分子（drivenByProfile 取自
     * 该 marker）。场景 5/6/7/8 的 marker targetId 为 null 或非 tweet.id，隐式关联不会误命中。
     */
    private fun computeScenarioStats(
        scenarioId: Int,
        allEvents: List<com.trae.social.core.data.entity.UserActionEventEntity>,
    ): ScenarioEffectStats {
        val domains = allEvents.mapNotNull { ProfileMappers.run { it.toDomain() } }

        // 1. 分离本场景的 marker 与所有真实互动事件
        val markers = mutableListOf<ScenarioMarker>()
        val realInteractions = mutableListOf<RealInteraction>()
        for (e in domains) {
            val eScenarioId = ProfileMappers.readExtraInt(e.extra, "scenarioId")
            val isMarker = ProfileMappers.readExtraBoolean(e.extra, "isScenarioMarker")
            val isInteraction = e.type.name in INTERACTION_TYPES
            if (eScenarioId == scenarioId && isMarker) {
                markers.add(
                    ScenarioMarker(
                        targetId = e.targetId,
                        driven = ProfileMappers.readExtraBoolean(e.extra, "drivenByProfile"),
                        exposure = ProfileMappers.readExtraInt(e.extra, "interactionCount") ?: 1,
                    )
                )
            } else if (isInteraction && !isMarker) {
                realInteractions.add(
                    RealInteraction(
                        targetId = e.targetId,
                        scenarioId = eScenarioId,
                        driven = ProfileMappers.readExtraBoolean(e.extra, "drivenByProfile"),
                    )
                )
            }
        }
        if (markers.isEmpty()) {
            return ScenarioEffectStats(scenarioId, 0, 0, 0.0, 0.0, 0.0)
        }

        // 2. marker targetId 索引（用于隐式关联；null/blank targetId 不参与）
        val markersByTargetId: Map<String, List<ScenarioMarker>> = markers
            .filter { !it.targetId.isNullOrBlank() }
            .groupBy { it.targetId!! }

        // 3. 统计曝光与互动
        var driven = 0
        var control = 0
        var drivenInteract = 0
        var controlInteract = 0
        for (m in markers) {
            if (m.driven) driven += m.exposure else control += m.exposure
        }
        for (i in realInteractions) {
            val attributedDriven: Boolean? = when {
                // 显式标记：event 自带 scenarioId=N（场景 5/6 路径，已经在 extra 中标记）
                i.scenarioId == scenarioId -> i.driven
                // 隐式关联：targetId 命中本场景 marker（场景 1/3/4 路径，
                // 真实用户互动事件不带 scenarioId，但 targetId 与 AI marker 相同）
                !i.targetId.isNullOrBlank() -> markersByTargetId[i.targetId!!]
                    ?.firstOrNull()?.driven
                else -> null
            }
            if (attributedDriven == null) continue
            if (attributedDriven) drivenInteract++ else controlInteract++
        }
        val drivenRate = if (driven == 0) 0.0 else drivenInteract.toDouble() / driven
        val controlRate = if (control == 0) 0.0 else controlInteract.toDouble() / control
        return ScenarioEffectStats(scenarioId, driven, control, drivenRate, controlRate, drivenRate - controlRate)
    }

    /** 单场景 marker 摘要（用于 computeScenarioStats 内部分组）。 */
    private data class ScenarioMarker(
        val targetId: String?,
        val driven: Boolean,
        val exposure: Int,
    )

    /** 真实用户互动事件摘要（用于 computeScenarioStats 内部分组）。 */
    private data class RealInteraction(
        val targetId: String?,
        val scenarioId: Int?,
        val driven: Boolean,
    )

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
