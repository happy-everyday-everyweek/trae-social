package com.trae.social.core.scheduler.di

import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.scheduler.ratelimit.DailyQuotaChecker
import com.trae.social.core.scheduler.ratelimit.SchedulerRateLimiter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 调度层 Hilt 模块（SubTask 8.6 DI）。
 *
 * 提供：
 * - [SchedulerRateLimiter]：按 [AiActivityLevel] 配置的全局限流器（单例）；
 * - [DailyQuotaChecker]：单账号每日推文配额检查器。
 *
 * Worker 类通过 @HiltWorker + @AssistedInject 自动绑定，由 HiltWorkerFactory 构造，
 * 无需在此显式 @Provides。
 *
 * 注意：使用 HiltWorkerFactory 需在 app 模块实现 Configuration.Provider
 * （见 [com.trae.social.app.SocialApp]）。
 */
@Module
@InstallIn(SingletonComponent::class)
object SchedulerModule {

    @Provides
    @Singleton
    fun provideSchedulerRateLimiter(): SchedulerRateLimiter {
        // 默认 MEDIUM，运行时由各 Worker 通过 reconfigure(level) 按当前档位切换
        return SchedulerRateLimiter(initialLevel = AiActivityLevel.MEDIUM)
    }

    @Provides
    @Singleton
    fun provideDailyQuotaChecker(
        tweetRepository: TweetRepository,
    ): DailyQuotaChecker = DailyQuotaChecker(tweetRepository)
}
