package com.trae.social.core.profiling.feedback

import com.trae.social.core.data.dao.UserProfileOverrideDao
import com.trae.social.core.data.model.FeedbackWeights
import com.trae.social.core.data.model.OverrideType
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.profiling.capture.ProfilingGate
import com.trae.social.core.profiling.mapping.ProfileMappers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 反哺力度控制 + 安全边界 + 灰度（#146 第四层）。
 *
 * 职责：
 * 1. [effectiveWeights]：clamp [0,0.8] → 场景级用户覆盖（DisableScenario→0）→ 置信度降权 → 全局灰度 → 采集关闭 ZERO。
 * 2. [shouldApply]：含场景覆盖 + 灰度分流。
 * 3. [selectDriven]：场景级配额 + 主题多样性 + 账号多样性约束辅助。
 *
 * 读激活版本（含回滚激活的旧版本），经 [ProfileCache] 缓存。
 */
@Singleton
class FeedbackController @Inject constructor(
    private val readAccess: UserProfileReadAccess,
    private val overrideDao: UserProfileOverrideDao,
    private val configRepository: ConfigRepository,
    private val cache: ProfileCache,
    private val gate: ProfilingGate,
) {

    /**
     * 计算生效反哺权重。
     *
     * 采集关闭 → ZERO；否则经 clamp + 场景覆盖 + 置信度降权 + 灰度。
     */
    fun effectiveWeights(): FeedbackWeights {
        if (!gate.isEnabled()) return FeedbackWeights.ZERO
        return readAccess.feedbackWeights()
    }

    /**
     * 判断某场景是否应应用反哺（含场景开关 + 灰度分流）。
     *
     * - 用户 DisableScenario 覆盖 → 强制 false（回退当前行为）。
     * - 灰度比例 < 1.0 → 按比例概率分流（基于 sessionId 哈希稳定分组）。
     */
    fun shouldApply(scenarioId: Int): Boolean {
        if (!gate.isEnabled()) return false
        if (isScenarioDisabledByUser(scenarioId)) return false
        val weights = effectiveWeights()
        val weight = FeedbackWeights.weightForScenario(scenarioId, weights)
        if (weight <= 0.0) return false
        return true
    }

    /** 带 sessionId 的灰度分流（稳定分组，同会话始终同组）。 */
    fun shouldApply(scenarioId: Int, sessionId: String): Boolean {
        if (!shouldApply(scenarioId)) return false
        val ratio = readAccess.let { runCatching { configRepository.getFeedbackGrayRatio() }.getOrDefault(1.0) }
        if (ratio >= 1.0) return true
        val bucket = (sessionId.hashCode().absoluteMod(100)) / 100.0
        return bucket < ratio
    }

    /**
     * 场景级配额 + 多样性约束：从 [pool] 中选出 driven 子集。
     *
     * @param scenarioId 场景编号。
     * @param sessionId 当前会话（用于配额统计）。
     * @param quotaRatio 该场景 driven 上限占比（如场景5=0.2）。
     * @param diversityKey 提取候选元素的主题/账号键，用于多样性约束。
     */
    fun <T> selectDriven(
        scenarioId: Int,
        sessionId: String,
        pool: List<T>,
        quotaRatio: Double,
        diversityKey: (T) -> String?,
    ): List<T> {
        if (!shouldApply(scenarioId, sessionId) || pool.isEmpty()) return emptyList()
        val quotaCount = maxOf(1, (pool.size * quotaRatio).toInt())
        // 主题多样性：避免全是 Top-1 主题，按 diversityKey 分组轮取
        val grouped = pool.groupBy { diversityKey(it) ?: "unknown" }
        val keys = grouped.keys.shuffled()
        val result = ArrayList<T>(quotaCount)
        var ki = 0
        while (result.size < quotaCount && grouped.isNotEmpty()) {
            val key = keys[ki % keys.size]
            val candidates = grouped[key] ?: continue
            if (candidates.isNotEmpty()) {
                result.add(candidates.first())
                grouped[key] = candidates.drop(1)
                if (grouped[key].isNullOrEmpty()) grouped.remove(key)
            }
            ki++
            if (ki > keys.size * quotaCount * 2) break // 安全退出
        }
        return result.take(quotaCount)
    }

    /** 用户是否通过 DisableScenario 覆盖强制关闭某场景。 */
    fun isScenarioDisabledByUser(scenarioId: Int): Boolean {
        cache.get("disabled_scenarios")?.let {
            @Suppress("UNCHECKED_CAST")
            return (it as Set<Int>).contains(scenarioId)
        }
        val disabled = readAccess.activeOverrides()
            .filter { it.type == OverrideType.SCENARIO_DISABLE }
            .mapNotNull { it.key.toIntOrNull() }
            .toSet()
        cache.put("disabled_scenarios", disabled)
        return scenarioId in disabled
    }

    private fun kotlin.Int.absoluteMod(mod: Int): Int {
        val r = this % mod
        return if (r < 0) r + mod else r
    }

    @Suppress("UNUSED")
    private fun unusedOverrideDaoRef(): UserProfileOverrideDao = overrideDao.also {
        ProfileMappers.run { /* 保持 import 使用，避免编译器移除依赖 */ }
    }
}
