package com.trae.social.onboarding

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 冷启动内容填充器（RISK-14）。
 *
 * 引导完成时调用，触发 20 个高活跃账号的即时内容生成，
 * 使主界面进入时已有可见的社交动态，避免空白冷启动体验。
 *
 * 仅在 onboarding 模块定义接口与 [DefaultColdStartFiller] 占位实现；
 * 具体绑定由 app 模块的 Hilt 模块提供（app 持有 core-scheduler 的
 * WorkManager 调度入口）。
 *
 * IMPL-45：onboarding 模块不再提供 [ColdStartFiller] 的默认 @Provides 绑定，
 * 避免与 app 模块的实现形成 Hilt DuplicateBindings。app 模块必须提供该绑定。
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
 * 默认空实现：app 模块可直接 @Binds 或 @Provides 使用此类作为占位实现。
 *
 * 真实实现可由 app 模块提供自定义 [ColdStartFiller] 实现，
 * 调用 core-scheduler 的 TweetGenerationWorker 等任务触发即时生成。
 */
@Singleton
class DefaultColdStartFiller @Inject constructor() : ColdStartFiller {

    override suspend fun triggerInitialFill() {
        // 默认无操作：由 app 模块提供真实实现以触发即时内容生成
    }
}
