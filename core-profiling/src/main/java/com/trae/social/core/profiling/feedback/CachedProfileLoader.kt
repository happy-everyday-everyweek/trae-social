package com.trae.social.core.profiling.feedback

import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.dao.UserProfileOverrideDao
import com.trae.social.core.data.model.OverrideRecord
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.data.model.UserProfileVersion
import com.trae.social.core.data.repository.ConfigRepository
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
    private val configRepository: ConfigRepository,
    private val gate: ProfilingGate,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var snapshot: UserProfileSnapshot? = null
    @Volatile private var activeVersion: UserProfileVersion? = null
    @Volatile private var overrides: List<OverrideRecord> = emptyList()
    @Volatile private var eventCount: Int = 0
    @Volatile private var grayRatio: Double = 1.0
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
                grayRatio = runCatching { configRepository.getFeedbackGrayRatio() }.getOrDefault(1.0)
                // 冷启动 seeding：取最早的 COLD_START_SEEDING 快照
                coldStartSeeding = snapshot?.takeIf { eventCount < ConfigRepository.COLD_START_THRESHOLD }?.interestVector
            }.onFailure { Timber.w(it, "CachedProfileLoader 刷新失败") }
        }
    }

    fun snapshot(): UserProfileSnapshot? = snapshot
    fun activeVersion(): UserProfileVersion? = activeVersion
    fun activeOverrides(): List<OverrideRecord> = overrides
    fun snapshotEventCount(): Int = eventCount
    fun grayRatio(): Double = grayRatio
    fun coldStartSeeding(): Map<String, Double>? = coldStartSeeding
}
