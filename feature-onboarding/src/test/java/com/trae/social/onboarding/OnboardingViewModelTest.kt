package com.trae.social.onboarding

import android.content.SharedPreferences
import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.llm.RulesetEngine
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [OnboardingViewModel] 单元测试桩（#280）。
 *
 * 当前覆盖：初始状态——selectedProvider=OPENAI、apiKey 为空、testStatus=Idle。
 * 后续可扩展：连通性测试、配置保存、历史 API Key 加载、provider 切换等。
 */
class OnboardingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `初始状态 selectedProvider 为 OPENAI`() = runTest(testDispatcher) {
        val viewModel = OnboardingViewModel(
            mockk(relaxed = true), // configRepository
            mockk(relaxed = true), // rulesetEngine
            mockk(relaxed = true), // coldStartFiller
            mockk(relaxed = true), // secureSharedPreferences
        )
        assertEquals(LlmProvider.OPENAI, viewModel.uiState.value.selectedProvider)
    }

    @Test
    fun `初始状态 apiKey 为空且 testStatus 为 Idle`() = runTest(testDispatcher) {
        val viewModel = OnboardingViewModel(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        val state = viewModel.uiState.value
        assertTrue(state.apiKey.isEmpty())
        assertTrue(state.testStatus is OnboardingViewModel.TestStatus.Idle)
    }
}
