package com.trae.social.timeline

import coil.ImageLoader
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.TweetRepository
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TimelineViewModel] 单元测试桩（#280）。
 *
 * 当前覆盖：初始状态——timelineFlow 为 Loading。
 * 后续可扩展：媒体推文分组、日期分组逻辑、重试触发、错误态等。
 */
class TimelineViewModelTest {

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
    fun `初始状态为 Loading`() = runTest(testDispatcher) {
        val viewModel = TimelineViewModel(
            mockk(relaxed = true), // tweetRepository
            mockk(relaxed = true), // accountRepository
            mockk(relaxed = true), // imageLoader
        )
        assertTrue(viewModel.timelineFlow.value is TimelineUiState.Loading)
    }
}
