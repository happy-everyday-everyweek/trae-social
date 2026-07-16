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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

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
 *
 * 第二轮 review Major 5 修复:原实现 `isEnabled()` 在缓存过期时 `runBlocking` 读取 DataStore,
 * 而 `UserActionTracker.trackNow` 被 `FeedViewModel` / `PublishViewModel` 等 UI 事件处理器
 * 直接调用(运行在主线程),runBlocking + DataStore IO 会触发 ANR。
 * 改为后台协程周期性刷新 `@Volatile enabledCache`,`isEnabled()` 仅读缓存,永不阻塞调用线程。
 */
private class ConfigProfilingGate(
    private val configRepository: ConfigRepository,
) : ProfilingGate {

    @Volatile
    private var enabledCache: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 后台协程周期性刷新缓存:首次立即读取一次,之后按 REFRESH_INTERVAL_MS 周期刷新。
        // 失败时仅 Timber.w 警告并沿用上次缓存值,不抛出。
        scope.launch {
            while (true) {
                runCatching { enabledCache = configRepository.isProfilingEnabled() }
                    .onFailure { Timber.w(it, "刷新 profiling 开关失败,沿用上次缓存值") }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun isEnabled(): Boolean = enabledCache

    override fun isDebug(): Boolean = false

    private companion object {
        const val REFRESH_INTERVAL_MS = 5_000L
    }
}
