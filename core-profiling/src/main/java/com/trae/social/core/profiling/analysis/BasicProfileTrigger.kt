package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.entity.UserProfileSnapshotEntity
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.profiling.capture.ProfilingGate
import com.trae.social.core.profiling.mapping.ProfileMappers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基础分析触发器（#146 第二层）。
 *
 * 双阈值触发：事件计数 >= [countThreshold]（100）或 距上次 >= [timeThresholdMs]（1h），
 * 先到即触发，debounce 30s。前台进入时强制检查一次。
 *
 * 全量重算兜底：每 6h 触发 FULL_RECOMPUTE 修正浮点漂移。
 */
@Singleton
class BasicProfileTrigger @Inject constructor(
    private val userActionDao: UserActionDao,
    private val userProfileDao: UserProfileDao,
    private val gate: ProfilingGate,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var lastTriggerAt: Long = 0L
    @Volatile private var lastFullRecomputeAt: Long = 0L
    @Volatile private var pending: Boolean = false

    /** 由 Tracker 批写后或前台进入时调用，按双阈值 + debounce 决定是否触发分析。 */
    fun maybeTrigger() {
        if (!gate.isEnabled()) return
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val since = maxOf(lastTriggerAt, now - TIME_THRESHOLD_MS)
                val count = userActionDao.countSince(since)
                val byTime = (now - lastTriggerAt) >= TIME_THRESHOLD_MS
                if (count >= COUNT_THRESHOLD || byTime) {
                    scheduleCompute(now)
                }
            } catch (t: Throwable) {
                Timber.w(t, "BasicProfileTrigger 检查失败")
            }
        }
    }

    /** 前台进入时强制检查一次（不等阈值）。 */
    fun forceCheckOnForeground() {
        if (!gate.isEnabled()) return
        scope.launch { scheduleCompute(System.currentTimeMillis(), force = true) }
    }

    private suspend fun scheduleCompute(now: Long, force: Boolean = false) {
        // debounce 30s
        if (!force && pending && now - lastTriggerAt < DEBOUNCE_MS) return
        mutex.withLock {
            if (!force && pending && System.currentTimeMillis() - lastTriggerAt < DEBOUNCE_MS) return
            pending = true
        }
        delay(DEBOUNCE_MS)
        runCatching { compute(now) }
            .onFailure { Timber.w(it, "基础分析计算失败") }
        pending = false
        lastTriggerAt = System.currentTimeMillis()
    }

    private suspend fun compute(now: Long) {
        val windowStart = now - ANALYSIS_WINDOW_MS
        val entities = userActionDao.queryBetween(windowStart, now)
        if (entities.isEmpty()) return
        val events = entities.mapNotNull { it.toDomain() }
        if (events.isEmpty()) return
        val previous = userProfileDao.latestSnapshot()?.toDomain()
        val doFullRecompute = (now - lastFullRecomputeAt) >= FULL_RECOMPUTE_INTERVAL_MS
        val snapshot = if (doFullRecompute && previous != null) {
            // 全量重算：忽略 previous 增量，从原始事件重算
            lastFullRecomputeAt = now
            BasicProfileAnalyzer.analyze(events, previous = null, now = now)
        } else {
            BasicProfileAnalyzer.analyze(events, previous = previous, now = now)
        }
        val source = if (doFullRecompute) SOURCE_FULL_RECOMPUTE else SOURCE_INCREMENTAL
        userProfileDao.insertSnapshot(snapshot.toEntity(source))
        Timber.i("基础分析完成 source=%s events=%d", source, events.size)
    }

    private fun com.trae.social.core.data.entity.UserActionEventEntity.toDomain() =
        ProfileMappers.run { toDomain() }

    private fun UserProfileSnapshotEntity.toDomain(): UserProfileSnapshot? =
        ProfileMappers.run { toDomain() }

    private fun UserProfileSnapshot.toEntity(source: String) =
        ProfileMappers.run { toEntity(source) }

    private companion object {
        const val COUNT_THRESHOLD = 100
        const val TIME_THRESHOLD_MS = 60 * 60 * 1000L // 1h
        const val DEBOUNCE_MS = 30_000L // 30s
        const val ANALYSIS_WINDOW_MS = 14L * 24 * 60 * 60 * 1000 // TTL 14 天
        const val FULL_RECOMPUTE_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6h
        const val SOURCE_INCREMENTAL = "INCREMENTAL"
        const val SOURCE_FULL_RECOMPUTE = "FULL_RECOMPUTE"
    }
}
