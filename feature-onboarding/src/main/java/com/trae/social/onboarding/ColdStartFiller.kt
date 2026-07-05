package com.trae.social.onboarding

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 冷启动内容填充器（RISK-14）。
 *
 * 引导完成时调用，触发 20 个高活跃账号的即时内容生成，
 * 使主界面进入时已有可见的社交动态，避免空白冷启动体验。
 *
 * 仅在 onboarding 模块定义接口；具体实现由 app 模块注入
 * （app 持有 core-scheduler 的 WorkManager 调度入口）。
 * 当 app 未提供实现时，使用 [DefaultColdStartFiller] 无操作占位，
 * 保证 onboarding 模块可独立编译运行。
 */
interface ColdStartFiller {

    /**
     * 触发冷启动内容填充。
     *
     * 实现方应异步执行（如入队 WorkManager），不应阻塞调用方。
     */
    suspend fun triggerInitialFill()
}

/**
 * 默认空实现：仅在 app 未注入真实实现时使用。
 *
 * 真实实现应由 app 模块通过 Hilt 模块覆盖绑定，
 * 调用 core-scheduler 的 TweetGenerationWorker 等任务触发即时生成。
 */
@Singleton
class DefaultColdStartFiller @Inject constructor() : ColdStartFiller {

    override suspend fun triggerInitialFill() {
        // 默认无操作：由 app 模块提供真实实现以触发即时内容生成
    }
}

/**
 * onboarding 模块 Hilt 装配。
 *
 * 提供 [ColdStartFiller] 的默认绑定；app 模块如需替换为真实实现，
 * 可在其自身 Hilt 模块中以更高优先级提供绑定（或移除本模块）。
 */
@Module
@InstallIn(SingletonComponent::class)
object OnboardingModule {

    @Provides
    @Singleton
    fun provideColdStartFiller(impl: DefaultColdStartFiller): ColdStartFiller = impl
}
