package com.trae.social.core.profiling.feedback

import com.trae.social.core.data.dao.UserProfileOverrideDao
import com.trae.social.core.data.entity.UserProfileOverrideEntity
import com.trae.social.core.data.model.FeedbackAction
import com.trae.social.core.data.model.OverrideRecord
import com.trae.social.core.data.model.OverrideType
import com.trae.social.core.data.model.sanitize
import com.trae.social.core.profiling.capture.ProfilingGate
import com.trae.social.core.profiling.mapping.ProfileMappers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 覆盖应用 + 冲突管理（#146 第五层，用户掌控层写侧）。
 *
 * 接收 [FeedbackAction]（不含回滚，回滚由 [ProfileVersionStore] 处理），
 * 应用为 [UserProfileOverrideEntity]：同 key 新覆盖产生时，
 * 旧覆盖 [UserProfileOverrideEntity.superseded] 置 true（软删除保留审计）。
 *
 * 应用后立即 [ProfileCache.invalidate] 使读侧下次读取合并新覆盖。
 *
 * 覆盖不衰减，直到用户撤销或 [resetAll]。
 */
@Singleton
class ProfileAdjuster @Inject constructor(
    private val overrideDao: UserProfileOverrideDao,
    private val cache: ProfileCache,
    private val loader: CachedProfileLoader,
    private val gate: ProfilingGate,
) {

    /** 覆盖应用串行化：避免并发 applyAll 导致同 key supersede 时序错乱。 */
    private val applyMutex = Mutex()

    /**
     * 应用一组 Action（不含回滚 Action）。
     *
     * - 白名单 + 值域校验：[sanitize] 失败的 Action 跳过。
     * - 回滚 Action（[FeedbackAction.RollbackProfileVersion]）忽略，由 [ProfileVersionStore] 处理。
     * - 同 key 新覆盖产生时，旧覆盖软删除保留审计。
     *
     * @param reason 触发该批覆盖的用户消息原文（审计用）。
     * @return 已应用的覆盖记录（不含被 sanitize 拒绝与回滚 Action）。
     */
    suspend fun applyAll(actions: List<FeedbackAction>, reason: String): List<OverrideRecord> =
        applyMutex.withLock {
            if (!gate.isEnabled()) return@withLock emptyList()
            val applied = ArrayList<OverrideRecord>()
            for (action in actions) {
                if (action is FeedbackAction.RollbackProfileVersion) continue
                val sanitized = action.sanitize() ?: continue
                val record = applyOne(sanitized, reason) ?: continue
                applied.add(record)
            }
            if (applied.isNotEmpty()) {
                cache.invalidate()
                loader.refresh()
                Timber.i("ProfileAdjuster 应用 %d 条覆盖", applied.size)
            }
            applied
        }

    /**
     * 重置所有覆盖（"重置所有调整"按钮）：物理删除全部覆盖（含已 superseded 的历史）。
     *
     * @return 删除的覆盖条数。
     */
    suspend fun resetAll(): Int = applyMutex.withLock {
        val count = overrideDao.deleteAll()
        cache.invalidate()
        loader.refresh()
        Timber.i("ProfileAdjuster 重置 %d 条覆盖", count)
        count
    }

    /** 当前生效覆盖（同步读缓存层）。 */
    fun activeOverrides(): List<OverrideRecord> = loader.activeOverrides()

    // ---- 内部 ----

    private suspend fun applyOne(action: FeedbackAction, reason: String): OverrideRecord? {
        val now = System.currentTimeMillis()
        return when (action) {
            is FeedbackAction.BoostTheme -> {
                val key = action.theme
                overrideDao.markSuperseded(OverrideType.THEME_BOOST.id, key)
                val record = OverrideRecord(
                    type = OverrideType.THEME_BOOST,
                    key = key,
                    value = "%.4f".format(action.weight),
                    reason = reason,
                    createdAt = now,
                    source = SOURCE_FEEDBACK_AGENT,
                )
                overrideDao.insert(record.toEntity())
                record
            }
            is FeedbackAction.SuppressTheme -> {
                val key = action.theme
                overrideDao.markSuperseded(OverrideType.THEME_SUPPRESS.id, key)
                val record = OverrideRecord(
                    type = OverrideType.THEME_SUPPRESS,
                    key = key,
                    value = "0.0",
                    reason = reason,
                    createdAt = now,
                    source = SOURCE_FEEDBACK_AGENT,
                )
                overrideDao.insert(record.toEntity())
                record
            }
            is FeedbackAction.AddPreference -> {
                val key = action.preference
                overrideDao.markSuperseded(OverrideType.ADD_PREFERENCE.id, key)
                val record = OverrideRecord(
                    type = OverrideType.ADD_PREFERENCE,
                    key = key,
                    value = "true",
                    reason = reason,
                    createdAt = now,
                    source = SOURCE_FEEDBACK_AGENT,
                )
                overrideDao.insert(record.toEntity())
                record
            }
            is FeedbackAction.RemovePreference -> {
                val key = action.preference
                overrideDao.markSuperseded(OverrideType.REMOVE_PREFERENCE.id, key)
                val record = OverrideRecord(
                    type = OverrideType.REMOVE_PREFERENCE,
                    key = key,
                    value = "true",
                    reason = reason,
                    createdAt = now,
                    source = SOURCE_FEEDBACK_AGENT,
                )
                overrideDao.insert(record.toEntity())
                record
            }
            is FeedbackAction.DisableScenario -> {
                val key = action.scenarioId.toString()
                overrideDao.markSuperseded(OverrideType.SCENARIO_DISABLE.id, key)
                val record = OverrideRecord(
                    type = OverrideType.SCENARIO_DISABLE,
                    key = key,
                    value = "true",
                    reason = reason,
                    createdAt = now,
                    source = SOURCE_FEEDBACK_AGENT,
                )
                overrideDao.insert(record.toEntity())
                record
            }
            is FeedbackAction.EnableScenario -> {
                // 启用场景 = 撤销该场景的 SCENARIO_DISABLE 覆盖
                val key = action.scenarioId.toString()
                overrideDao.markSuperseded(OverrideType.SCENARIO_DISABLE.id, key)
                null
            }
            is FeedbackAction.CorrectNarrative -> {
                val key = "narrative"
                overrideDao.markSuperseded(OverrideType.CORRECT_NARRATIVE.id, key)
                val record = OverrideRecord(
                    type = OverrideType.CORRECT_NARRATIVE,
                    key = key,
                    value = action.correction,
                    reason = reason,
                    createdAt = now,
                    source = SOURCE_FEEDBACK_AGENT,
                )
                overrideDao.insert(record.toEntity())
                record
            }
            is FeedbackAction.SetActiveHours -> {
                val key = "active_hours"
                overrideDao.markSuperseded(OverrideType.SET_ACTIVE_HOURS.id, key)
                val record = OverrideRecord(
                    type = OverrideType.SET_ACTIVE_HOURS,
                    key = key,
                    value = action.hours.joinToString(","),
                    reason = reason,
                    createdAt = now,
                    source = SOURCE_FEEDBACK_AGENT,
                )
                overrideDao.insert(record.toEntity())
                record
            }
            is FeedbackAction.RollbackProfileVersion -> null
        }
    }

    private fun OverrideRecord.toEntity() = ProfileMappers.run { toEntity() }

    private companion object {
        const val SOURCE_FEEDBACK_AGENT = "FEEDBACK_AGENT"
    }
}
