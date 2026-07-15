package com.trae.social.core.profiling.feedback

import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.dao.UserProfileOverrideDao
import com.trae.social.core.data.model.FeedbackWeights
import com.trae.social.core.data.model.OverrideRecord
import com.trae.social.core.data.model.OverrideType
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.data.model.UserProfileVersion
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.profiling.capture.ProfilingGate
import com.trae.social.core.profiling.mapping.ProfileMappers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 业务侧读取入口（#146 第四层，读侧）。
 *
 * 覆盖优先级：用户显式覆盖 > 激活的 LLM 版本 > 基础快照 > 冷启动 seeding。
 * 置信度降权：effectiveWeight = feedbackWeight × confidence。
 * 经 [ProfileCache] 缓存；画像为空时返回 null / 全 0 权重，业务侧零回归。
 */
interface UserProfileReadAccess {
    fun latestSnapshot(): UserProfileSnapshot?
    fun activeVersion(): UserProfileVersion?
    fun interestVector(): Map<String, Double>
    fun feedbackWeights(): FeedbackWeights
    fun activeHours(): List<Int>
    fun isColdStart(): Boolean
    fun coldStartSeeding(): Map<String, Double>?
    fun activeOverrides(): List<OverrideRecord>
}

/**
 * [UserProfileReadAccess] 默认实现。
 *
 * 注意：DAO 为 suspend，本接口设计为同步读取业务侧高频调用。因此实现内部用缓存快照，
 * 缓存未命中时通过 [CachedProfileLoader] 异步刷新并返回当前最佳估值（可能为 null/零）。
 * 缓存由 [ProfileCache] 管理 TTL，[com.trae.social.core.profiling.analysis.BasicProfileTrigger]
 * 与 ProfileAdjuster/VersionStore 变更后主动 invalidate。
 */
@Singleton
class UserProfileReadAccessImpl @Inject constructor(
    private val userProfileDao: UserProfileDao,
    private val overrideDao: UserProfileOverrideDao,
    private val configRepository: ConfigRepository,
    private val cache: ProfileCache,
    private val loader: CachedProfileLoader,
    private val gate: ProfilingGate,
) : UserProfileReadAccess {

    override fun latestSnapshot(): UserProfileSnapshot? = loader.snapshot()

    override fun activeVersion(): UserProfileVersion? = loader.activeVersion()

    override fun interestVector(): Map<String, Double> {
        if (!gate.isEnabled()) return emptyMap()
        cache.get(KEY_INTEREST)?.let { @Suppress("UNCHECKED_CAST") return it as Map<String, Double> }
        val snapshot = loader.snapshot() ?: return coldStartSeeding() ?: emptyMap()
        val base = snapshot.interestVector
        val merged = applyThemeOverrides(base)
        cache.put(KEY_INTEREST, merged)
        return merged
    }

    override fun feedbackWeights(): FeedbackWeights {
        if (!gate.isEnabled()) return FeedbackWeights.ZERO
        cache.get(KEY_WEIGHTS)?.let { @Suppress("UNCHECKED_CAST") return it as FeedbackWeights }
        val version = loader.activeVersion()
        val raw = version?.feedbackWeights ?: FeedbackWeights.ZERO
        val clamped = clampWeights(raw)
        val withConfidence = applyConfidence(clamped, loader.snapshot())
        val result = applyGrayRatio(withConfidence)
        cache.put(KEY_WEIGHTS, result)
        return result
    }

    override fun activeHours(): List<Int> {
        if (!gate.isEnabled()) return emptyList()
        cache.get(KEY_HOURS)?.let { @Suppress("UNCHECKED_CAST") return it as List<Int> }
        val overrideHours = loader.activeOverrides()
            .firstOrNull { it.type == OverrideType.SET_ACTIVE_HOURS }?.value
            ?.let { ProfileMappers.run { runCatching { it.split(",").map { h -> h.trim().toInt() } }.getOrNull() } }
        val result = overrideHours ?: loader.snapshot()?.activeHours ?: emptyList()
        cache.put(KEY_HOURS, result)
        return result
    }

    override fun isColdStart(): Boolean =
        loader.snapshot() == null && (loader.snapshotEventCount() < ConfigRepository.COLD_START_THRESHOLD)

    override fun coldStartSeeding(): Map<String, Double>? = loader.coldStartSeeding()

    override fun activeOverrides(): List<OverrideRecord> = loader.activeOverrides()

    // ---- 合并逻辑 ----

    private fun applyThemeOverrides(base: Map<String, Double>): Map<String, Double> {
        val overrides = loader.activeOverrides()
        if (overrides.isEmpty()) return base
        val result = base.toMutableMap()
        overrides.forEach { o ->
            when (o.type) {
                OverrideType.THEME_BOOST -> {
                    val weight = o.value.toDoubleOrNull() ?: return@forEach
                    result[o.key] = weight
                }
                OverrideType.THEME_SUPPRESS -> result[o.key] = 0.0
                else -> {}
            }
        }
        return ProfileMappers.run { normalize(result) }
    }

    private fun clampWeights(w: FeedbackWeights): FeedbackWeights = FeedbackWeights(
        topicBias = w.topicBias.coerceIn(0.0, 0.8),
        accountPriority = w.accountPriority.coerceIn(0.0, 0.8),
        interactionAffinity = w.interactionAffinity.coerceIn(0.0, 0.8),
        commentPersona = w.commentPersona.coerceIn(0.0, 0.8),
        feedBoost = w.feedBoost.coerceIn(0.0, 0.8),
        followRecommend = w.followRecommend.coerceIn(0.0, 0.8),
        personaCoEvolve = w.personaCoEvolve.coerceIn(0.0, 0.8),
        interactionTiming = w.interactionTiming.coerceIn(0.0, 0.8),
    )

    /** 置信度降权：每字段乘以对应维度 confidence（effectiveWeight = feedbackWeight × confidence）。 */
    private fun applyConfidence(w: FeedbackWeights, snapshot: UserProfileSnapshot?): FeedbackWeights {
        val c = snapshot?.confidence ?: return w
        return FeedbackWeights(
            topicBias = w.topicBias * c.interestVector,
            accountPriority = w.accountPriority * c.interestVector,
            interactionAffinity = w.interactionAffinity * c.socialStyle,
            commentPersona = w.commentPersona * c.interactionTendency,
            feedBoost = w.feedBoost * c.interestVector,
            followRecommend = w.followRecommend * c.socialStyle,
            personaCoEvolve = w.personaCoEvolve * c.overall,
            interactionTiming = w.interactionTiming * c.activeHours,
        )
    }

    private fun applyGrayRatio(w: FeedbackWeights): FeedbackWeights {
        val ratio = loader.grayRatio()
        if (ratio >= 1.0) return w
        return FeedbackWeights(
            topicBias = w.topicBias * ratio,
            accountPriority = w.accountPriority * ratio,
            interactionAffinity = w.interactionAffinity * ratio,
            commentPersona = w.commentPersona * ratio,
            feedBoost = w.feedBoost * ratio,
            followRecommend = w.followRecommend * ratio,
            personaCoEvolve = w.personaCoEvolve * ratio,
            interactionTiming = w.interactionTiming * ratio,
        )
    }

    private companion object {
        const val KEY_INTEREST = "interest_vector"
        const val KEY_WEIGHTS = "feedback_weights"
        const val KEY_HOURS = "active_hours"
    }
}

private fun ProfileMappers.normalize(map: Map<String, Double>): Map<String, Double> {
    val sum = map.values.sum()
    return if (sum <= 0.0) map.filterValues { it > 0.0 } else map.mapValues { it.value / sum }
}
