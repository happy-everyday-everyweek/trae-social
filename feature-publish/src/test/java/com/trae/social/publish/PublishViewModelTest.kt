package com.trae.social.publish

import android.content.Context
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.InteractionRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.profiling.capture.SessionManager
import com.trae.social.core.profiling.capture.UserActionTracker
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PublishViewModel] 单元测试桩（#280）。
 *
 * 当前覆盖：初始状态——uiState 默认值、publishPhase 为 IDLE。
 * 后续可扩展：addCapture/removeCapture、publish 流程、发布失败事件等。
 */
class PublishViewModelTest {

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
    fun `初始状态 uiState 为默认值`() = runTest(testDispatcher) {
        val viewModel = PublishViewModel(
            mockk(relaxed = true), // appContext
            mockk(relaxed = true), // tweetRepository
            mockk(relaxed = true), // interactionRepository
            mockk(relaxed = true), // accountRepository
            mockk(relaxed = true), // userActionTracker
            mockk(relaxed = true), // sessionManager
        )
        val state = viewModel.uiState.value
        assertTrue(state.captures.isEmpty())
        assertEquals("", state.caption)
        assertEquals(CaptureRatio.RATIO_4_3, state.selectedRatio)
        assertEquals(FlashMode.OFF, state.flashMode)
        assertFalse(state.isPublishing)
    }

    @Test
    fun `初始状态 publishPhase 为 IDLE`() = runTest(testDispatcher) {
        val viewModel = PublishViewModel(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertEquals(PublishPhase.IDLE, viewModel.publishPhase.value)
    }
}
