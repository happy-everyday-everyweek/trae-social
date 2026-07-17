package com.trae.social.core.profiling.feedback

import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.dao.UserProfileOverrideDao
import com.trae.social.core.data.model.OverrideRecord
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.data.model.UserProfileVersion
import com.trae.social.core.profiling.capture.ProfilingGate
import com.trae.social.core.profiling.mapping.ProfileMappers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 画像数据加载器（#146 第四层）。
 *
 * [UserProfileReadAccess] 为同步接口供业务侧高频调用，但底层 DAO 为 suspend。
 * 本加载器维护内存快照，由 [refresh] 异步刷新；缓存变更后由 [ProfileCache.invalidate]
 * 触发重新加载。同步访问返回最近一次刷新的估值（启动初期可能为 null，业务侧零回归）。
 */
@Singleton
class CachedProfileLoader @Inject constructor(
    private val userProfileDao: UserProfileDao,
    private val overrideDao: UserProfileOverrideDao,
    private val userActionDao: UserActionDao,
    private val gate: ProfilingGate,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var snapshot: UserProfileSnapshot? = null
    @Volatile private var activeVersion: UserProfileVersion? = null
    @Volatile private var overrides: List<OverrideRecord> = emptyList()
    @Volatile private var eventCount: Int = 0
    // 第六轮 review B3 修复（文档化）：COLD_START_SEEDING 快照的写入方（onboarding 兴趣选择
    // UI）未在本 PR 实现，故 coldStartSeeding 当前恒为 null。读侧接口
    // （UserProfileReadAccess.coldStartSeeding / isColdStart）保留以维持 API 契约，
    // 待 onboarding 落地后写入 source=COLD_START_SEEDING 的快照即可激活冷启动个性化。
    //
    // 旧实现的 bug：coldStartSeeding = snapshot?.takeIf { eventCount < THRESHOLD }?.interestVector
    // 读的是最新快照（非最早）、无 source 过滤；且 isColdStart() 仅在 snapshot==null 时 true，
    // 那时 coldStartSeeding 也为 null → interestVector() 在冷启动期恒返回 emptyMap()，
    // onboarding 选的兴趣（若有）根本不会影响个性化。本修复移除误导性计算，诚实返回 null，
    // 待写入方落地后再接通读侧。
    @Volatile private var coldStartSeeding: Map<String, Double>? = null

    init {
        // 启动时异步加载一次，避免业务侧首次读取全为空
        refresh()
    }

    /** 异步刷新内存缓存。覆盖 / 快照 / 版本激活变更后调用。 */
    fun refresh() {
        if (!gate.isEnabled()) {
            snapshot = null
            activeVersion = null
            overrides = emptyList()
            return
        }
        scope.launch {
            runCatching {
                snapshot = userProfileDao.latestSnapshot()?.let { ProfileMappers.run { it.toDomain() } }
                activeVersion = userProfileDao.activeVersion()?.let { ProfileMappers.run { it.toDomain() } }
                    ?: userProfileDao.latestVersion()?.let { ProfileMappers.run { it.toDomain() } }
                overrides = overrideDao.active().mapNotNull { ProfileMappers.run { it.toDomain() } }
                eventCount = userActionDao.countAll()
                // B3 修复：coldStartSeeding 保持 null（onboarding 兴趣选择 UI 待实现，见字段注释）
                // M2 修复：grayRatio 不再缓存（applyGrayRatio 已移除，灰度由 FeedbackController.shouldApply 桶分实现）
            }.onFailure { Timber.w(it, "CachedProfileLoader 刷新失败") }
        }
    }

    fun snapshot(): UserProfileSnapshot? = snapshot
    fun activeVersion(): UserProfileVersion? = activeVersion
    fun activeOverrides(): List<OverrideRecord> = overrides
    fun snapshotEventCount(): Int = eventCount
    fun coldStartSeeding(): Map<String, Double>? = coldStartSeeding
}
