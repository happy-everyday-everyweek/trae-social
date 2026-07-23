package com.trae.social.feed

import coil.ImageLoader
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.CommentRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.InteractionRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.profiling.capture.SessionManager
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.core.profiling.feedback.FeedbackController
import com.trae.social.core.profiling.feedback.UserProfileReadAccess
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
 * [FeedViewModel] 单元测试桩（#280）。
 *
 * 当前覆盖：初始状态——feedBoostEnabled=false、profileInterests 为空。
 * 后续可扩展：点赞/收藏/不感兴趣乐观更新、Paging flow 测试、画像 boost 开关等。
 */
class FeedViewModelTest {

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
    fun `初始状态 feedBoostEnabled 为 false`() = runTest(testDispatcher) {
        val viewModel = FeedViewModel(
            mockk(relaxed = true), // tweetRepository
            mockk(relaxed = true), // accountRepository
            mockk(relaxed = true), // interactionRepository
            mockk(relaxed = true), // commentRepository
            mockk(relaxed = true), // configRepository
            mockk(relaxed = true), // userActionTracker
            mockk(relaxed = true), // sessionManager
            mockk(relaxed = true), // readAccess
            mockk(relaxed = true), // feedbackController
            mockk(relaxed = true), // imageLoader
        )
        assertFalse(viewModel.feedBoostEnabled.value)
    }

    @Test
    fun `初始状态 profileInterests 为空列表`() = runTest(testDispatcher) {
        val viewModel = FeedViewModel(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertTrue(viewModel.profileInterests.value.isEmpty())
    }
}
