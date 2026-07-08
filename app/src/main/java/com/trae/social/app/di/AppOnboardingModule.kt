package com.trae.social.app.di

import com.trae.social.onboarding.ColdStartFiller
import com.trae.social.onboarding.DefaultColdStartFiller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * app 模块 onboarding 相关 Hilt 装配。
 *
 * IMPL-45：[ColdStartFiller] 的绑定由 app 模块提供，
 * feature-onboarding 模块不再提供默认绑定，避免 DuplicateBindings。
 * 当前使用 [DefaultColdStartFiller] 占位实现；后续可替换为真实实现
 * （调用 core-scheduler 的 TweetGenerationWorker 触发即时内容生成）。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppOnboardingModule {

    @Provides
    @Singleton
    fun provideColdStartFiller(impl: DefaultColdStartFiller): ColdStartFiller = impl
}
