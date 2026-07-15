package com.trae.social.core.profiling.di

import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.entity.UserActionEventEntity
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.profiling.capture.ProfilingGate
import com.trae.social.core.profiling.capture.UserActionSink
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.core.profiling.capture.UserActionTrackerImpl
import com.trae.social.core.profiling.feedback.UserProfileReadAccess
import com.trae.social.core.profiling.feedback.UserProfileReadAccessImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * core-profiling 模块 Hilt 装配（#146）。
 *
 * - [UserActionSink]：将 [UserActionTrackerImpl] 与 Room [UserActionDao] 解耦，
 *   便于单测注入 fake；此处绑定 DAO 实现。
 * - [ProfilingGate]：读取 [ConfigRepository.isProfilingEnabled] 与调试旁路。
 * - [UserActionTracker] / [UserProfileReadAccess]：绑定接口与实现。
 */
@Module
@InstallIn(SingletonComponent::class)
object ProfilingModule {

    @Provides
    @Singleton
    fun provideUserActionSink(dao: UserActionDao): UserActionSink = DaoUserActionSink(dao)

    @Provides
    @Singleton
    fun provideProfilingGate(configRepository: ConfigRepository): ProfilingGate =
        ConfigProfilingGate(configRepository)

    @Provides
    @Singleton
    fun provideUserActionTracker(
        sink: UserActionSink,
        gate: ProfilingGate,
    ): UserActionTracker = UserActionTrackerImpl(sink, gate)

    @Provides
    @Singleton
    fun provideUserProfileReadAccess(
        impl: UserProfileReadAccessImpl,
    ): UserProfileReadAccess = impl
}

/**
 * [UserActionSink] 的 DAO 实现：将事件写入 Room。
 */
private class DaoUserActionSink(
    private val dao: UserActionDao,
) : UserActionSink {
    override suspend fun insertAll(events: List<UserActionEventEntity>) {
        dao.insertAll(events)
    }
}

/**
 * [ProfilingGate] 的实现：从 [ConfigRepository] 读取采集开关；
 * 调试旁路读取 profiling_debug DataStore key（此处复用 isProfilingEnabled，简化）。
 */
private class ConfigProfilingGate(
    private val configRepository: ConfigRepository,
) : ProfilingGate {

    @Volatile
    private var enabledCache: Boolean = true
    @Volatile
    private var cacheAt: Long = 0L

    override fun isEnabled(): Boolean {
        val now = System.currentTimeMillis()
        if (now - cacheAt < GATE_CACHE_TTL_MS) return enabledCache
        // 同步返回缓存值，后台异步刷新（ConfigRepository 为 suspend）
        // 注意：runBlocking 在 IO 线程上短暂使用，避免阻塞 UI；
        // Tracker 调用方已在 IO 上下文，此处仅作为容错兜底，主路径仍由调用方应启用缓存读取。
        cacheAt = now
        kotlinx.coroutines.runCatching {
            kotlinx.coroutines.runBlocking {
                enabledCache = configRepository.isProfilingEnabled()
            }
        }
        return enabledCache
    }

    override fun isDebug(): Boolean = false

    private companion object {
        const val GATE_CACHE_TTL_MS = 5_000L
    }
}
