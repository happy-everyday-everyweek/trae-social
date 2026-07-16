package com.trae.social.core.profiling.feedback

import androidx.room.withTransaction
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.dao.UserProfileOverrideDao
import com.trae.social.core.data.dao.UserProfileRollbackDao
import com.trae.social.core.data.db.AppDatabase
import com.trae.social.core.data.entity.UserProfileRollbackEntity
import com.trae.social.core.data.model.DoubleDelta
import com.trae.social.core.data.model.FeedbackAction
import com.trae.social.core.data.model.FeedbackWeights
import com.trae.social.core.data.model.OverrideRecord
import com.trae.social.core.data.model.RollbackPreview
import com.trae.social.core.data.model.RollbackRecord
import com.trae.social.core.data.model.RollbackResult
import com.trae.social.core.data.model.UserProfileVersion
import com.trae.social.core.data.model.VersionSummary
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.profiling.capture.ProfilingGate
import com.trae.social.core.profiling.mapping.ProfileMappers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 版本库 + 历史检索 + 回滚（#146 第五层，用户掌控层写侧）。
 *
 * - 所有版本永久保留（受 [ConfigRepository.MAX_PROFILE_VERSIONS] 上限保护，超限删最旧非激活）。
 * - 回滚 = 激活旧版本（不删除新版本），保留完整审计链。
 * - 回滚后用户覆盖仍生效（覆盖是用户显式意志，不因回滚丢失）。
 *
 * 激活版本变更后立即 [ProfileCache.invalidate]，使读侧下次读取返回新激活版本。
 */
@Singleton
class ProfileVersionStore @Inject constructor(
    private val database: AppDatabase,
    private val versionDao: UserProfileDao,
    private val overrideDao: UserProfileOverrideDao,
    private val rollbackDao: UserProfileRollbackDao,
    private val cache: ProfileCache,
    private val loader: CachedProfileLoader,
    private val gate: ProfilingGate,
) {

    /** 版本激活 / 回滚串行化，避免并发回滚导致 active 状态错乱。 */
    private val activationMutex = Mutex()

    /** 当前激活版本（可能是最新，也可能是回滚的旧版本）。 */
    fun activeVersion(): UserProfileVersion? = loader.activeVersion()

    /** 最近 N 个版本的摘要（供智能体 prompt 与 DevOptions 展示）。 */
    suspend fun recentSummaries(limit: Int): List<VersionSummary> =
        versionDao.recentVersions(limit).map { ProfileMappers.run { it.toSummary() } }

    /**
     * 按定位参数检索目标版本。
     *
     * 三种定位方式（最多一个非空）：
     * - [FeedbackAction.RollbackProfileVersion.versionId]：精确回滚到指定版本。
     * - [FeedbackAction.RollbackProfileVersion.aroundTimestamp]：该时间点之前最近的版本。
     * - [FeedbackAction.RollbackProfileVersion.narrativeKeyword]：narrative 含关键词的最近版本。
     * - 全部为空：当前激活版本的上一个版本（"回到上个版本"语义）。
     */
    suspend fun locate(action: FeedbackAction.RollbackProfileVersion): UserProfileVersion? {
        // 跨模块 public API property 无法 smart cast，用 ?.let 捕获非空局部变量。
        action.versionId?.let { id -> return versionDao.versionById(id)?.toDomain() }
        action.aroundTimestamp?.let { ts ->
            return versionDao.versionsBeforeTime(ts, 1).firstOrNull()?.toDomain()
        }
        action.narrativeKeyword?.let { kw ->
            // 第五轮 review N3 修复:转义 LIKE 通配符(%) / (_) / 反斜杠(\),
            // 避免用户消息含这些字符时被 SQLite 当作模式匹配符,导致回滚定位命中非预期版本。
            // 例如"回滚到 100% 那个版本" → 未转义的 pattern `%100%%` 会命中任意包含 100 的 narrative。
            // DAO 侧已添加 `ESCAPE '\'` 子句与此处配合。
            val escaped = kw
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            return versionDao.versionsByNarrativeKeyword("%$escaped%", 1).firstOrNull()?.toDomain()
        }
        // 全部为空 → 取当前激活版本的上一个版本("回到上个版本"语义)
        // 第二轮 review Major 3 修复:旧实现 `recentVersions(2).firstOrNull { it.id != active.id }`
        // 在回滚态下会返回更新版本——如 V2 active 而 V5 是最新时,`recentVersions(2)` 返回 [V5, V4],
        // `firstOrNull { it.id != active.id }` = V5(比 active 更新),违反"回到上个版本"语义。
        // 改为查询比 active.createdAt 严格更早的最近版本,正确表达"上一个版本"。
        val active = versionDao.activeVersion() ?: versionDao.latestVersion() ?: return null
        return versionDao.versionsStrictlyBeforeTime(active.createdAt, 1).firstOrNull()?.toDomain()
    }

    /**
     * 生成回滚预览（差异对比），不应用。
     *
     * 包含：narrative 对比 / feedbackWeights 各字段变化 / 受影响场景 / 回滚后保留的覆盖。
     */
    suspend fun previewRollback(action: FeedbackAction.RollbackProfileVersion): RollbackPreview? {
        if (!gate.isEnabled()) return null
        val target = locate(action) ?: return null
        val current = activeVersion() ?: return null
        if (target.id == current.id) return null
        val overrides = overrideDao.active().mapNotNull { ProfileMappers.run { it.toDomain() } }
        val affected = affectedScenarios(current.feedbackWeights, target.feedbackWeights)
        return RollbackPreview(
            targetVersionId = target.id,
            targetCreatedAt = target.createdAt,
            targetNarrative = target.narrative,
            currentNarrative = current.narrative,
            feedbackWeightsDiff = diffWeights(current.feedbackWeights, target.feedbackWeights),
            overridesToPreserve = overrides,
            affectedScenarios = affected,
        )
    }

    /**
     * 应用回滚：激活旧版本（不删除新版本）。
     *
     * - 将目标版本标记为 active；当前激活版本取消 active。
     * - 保留所有用户覆盖（不删除）。
     * - 失效 [ProfileCache]。
     * - 不删除新版本，保留完整审计链，用户可再次回滚到新版本。
     * - 写一条 [UserProfileRollbackEntity] 记录（fromVersionId, toVersionId, reason）。
     * - 超过 [ConfigRepository.MAX_PROFILE_VERSIONS] 上限时删除最旧非激活版本。
     */
    suspend fun applyRollback(versionId: Long, reason: String): RollbackResult =
        activationMutex.withLock {
            val from = (versionDao.activeVersion() ?: versionDao.latestVersion())?.toDomain()
            val to = versionDao.versionById(versionId)?.toDomain()
                ?: throw IllegalArgumentException("回滚目标版本不存在: $versionId")
            val rollbackEntity = UserProfileRollbackEntity(
                fromVersionId = from?.id ?: 0L,
                toVersionId = versionId,
                reason = reason,
                appliedAt = System.currentTimeMillis(),
            )
            // M-反馈3 修复：回滚多步写（取消当前 active + 设目标 active + 清理超额版本 + 写回滚记录）
            // 包在 Room 事务内，中途失败整体回滚，避免出现 active 错乱或回滚记录缺失的脏状态。
            // trimExcessVersions 的 runCatching 仍保留：其失败不致整事务回滚（仅吞掉超额清理）。
            database.withTransaction {
                versionDao.setActive(versionId)
                runCatching { trimExcessVersions() }
                rollbackDao.insert(rollbackEntity)
            }
            cache.invalidate()
            loader.refresh()
            val result = RollbackResult(
                fromVersionId = from?.id ?: 0L,
                toVersionId = versionId,
                appliedAt = System.currentTimeMillis(),
            )
            Timber.i("回滚画像版本 from=%s to=%s", result.fromVersionId, result.toVersionId)
            result
        }

    /** 回滚历史（审计）。 */
    suspend fun rollbackHistory(): List<RollbackRecord> =
        rollbackDao.all().map { ProfileMappers.run { it.toDomain() } }

    /**
     * 新版本产生时由 [com.trae.social.core.scheduler.work.UserProfileWorker] 调用：
     * 设新版本为 active，取消其他版本 active。
     */
    suspend fun activateNewVersion(versionId: Long) = activationMutex.withLock {
        versionDao.setActive(versionId)
        runCatching { trimExcessVersions() }
        cache.invalidate()
        loader.refresh()
    }

    /**
     * 第二轮 review Major 2 修复:原子插入并激活新版本。
     *
     * 旧实现(Worker 内两步):
     * 1. `insertVersion(isActive=true)` → DB 中暂时存在旧 active 与新版本两条 isActive=1
     * 2. `activateNewVersion(newId)` → 才清除旧 active
     * 若 Worker 在两步之间被系统杀死,双 active 状态永久残留,
     * 后续 `activeVersion()` (`SELECT ... WHERE isActive=1 LIMIT 1`) 返回哪个版本不可预期。
     *
     * 此实现:`database.withTransaction { insertVersion(isActive=false) + setActive(newId) + trimExcessVersions }`,
     * insert 与激活在同一 Room 事务内,任何中途失败整体回滚,不会留下双 active。
     *
     * 第五轮 review N4 修复:旧实现 insertVersion 在 withTransaction 之外执行,与注释描述的
     * "insert 与激活在同一 Room 事务内"不符。虽因 isActive=false 功能上仍安全(中途失败只会留下
     * 一条未激活的孤儿版本,由 trim 清理),但注释与实现不一致。现将 insertVersion 移入事务,
     * 真正实现单事务原子性。
     */
    suspend fun insertAndActivate(version: UserProfileVersion): Long = activationMutex.withLock {
        // 强制 isActive=false 后插入,insert 与激活由同一 Room 事务原子完成
        val entity = ProfileMappers.run { version.toEntity() }.copy(isActive = false)
        val newId = database.withTransaction {
            val id = versionDao.insertVersion(entity)
            versionDao.setActive(id)
            runCatching { trimExcessVersions() }
            id
        }
        cache.invalidate()
        loader.refresh()
        newId
    }

    // ---- 内部 ----

    private suspend fun trimExcessVersions() {
        val count = versionDao.versionCount()
        val max = ConfigRepository.MAX_PROFILE_VERSIONS
        if (count > max) {
            versionDao.deleteOldestInactive(count - max)
        }
    }

    private fun diffWeights(current: FeedbackWeights, target: FeedbackWeights): Map<String, DoubleDelta> {
        val result = LinkedHashMap<String, DoubleDelta>()
        result["topicBias"] = DoubleDelta(current.topicBias, target.topicBias)
        result["accountPriority"] = DoubleDelta(current.accountPriority, target.accountPriority)
        result["interactionAffinity"] = DoubleDelta(current.interactionAffinity, target.interactionAffinity)
        result["commentPersona"] = DoubleDelta(current.commentPersona, target.commentPersona)
        result["feedBoost"] = DoubleDelta(current.feedBoost, target.feedBoost)
        result["followRecommend"] = DoubleDelta(current.followRecommend, target.followRecommend)
        result["personaCoEvolve"] = DoubleDelta(current.personaCoEvolve, target.personaCoEvolve)
        result["interactionTiming"] = DoubleDelta(current.interactionTiming, target.interactionTiming)
        return result
    }

    private fun affectedScenarios(current: FeedbackWeights, target: FeedbackWeights): List<Int> {
        val result = ArrayList<Int>()
        for (scenarioId in 1..8) {
            val c = FeedbackWeights.weightForScenario(scenarioId, current)
            val t = FeedbackWeights.weightForScenario(scenarioId, target)
            if (kotlin.math.abs(c - t) > 0.001) result.add(scenarioId)
        }
        return result
    }

    private fun com.trae.social.core.data.entity.UserProfileVersionEntity.toDomain(): UserProfileVersion? =
        ProfileMappers.run { toDomain() }
}
