package com.trae.social.app.di

import com.trae.social.onboarding.ColdStartFiller
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
 *
 * IMPL-1：使用 [AppColdStartFiller] 真实实现，入队 TweetGenerationWorker
 * 触发即时内容生成，取代 [com.trae.social.onboarding.DefaultColdStartFiller] 空实现。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppOnboardingModule {

    @Provides
    @Singleton
    fun provideColdStartFiller(impl: AppColdStartFiller): ColdStartFiller = impl
}
