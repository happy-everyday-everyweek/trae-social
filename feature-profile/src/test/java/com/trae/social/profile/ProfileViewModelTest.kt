package com.trae.social.profile

import androidx.lifecycle.SavedStateHandle
import coil.ImageLoader
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.FollowRelationRepository
import com.trae.social.core.data.repository.InteractionRepository
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
 * [ProfileViewModel] 单元测试桩（#280）。
 *
 * 当前覆盖：初始状态——uiState 为 Loading、isSelfProfile 为 true（无 accountId 路由参数）。
 * 后续可扩展：账号资料加载、推文/媒体列表、关注统计、AI 活跃度档位读取等。
 */
class ProfileViewModelTest {

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
        val viewModel = ProfileViewModel(
            mockk(relaxed = true), // accountRepository
            mockk(relaxed = true), // tweetRepository
            mockk(relaxed = true), // configRepository
            mockk(relaxed = true), // followRelationRepository
            mockk(relaxed = true), // interactionRepository
            mockk(relaxed = true), // savedStateHandle
            mockk(relaxed = true), // imageLoader
        )
        assertTrue(viewModel.uiState.value is ProfileUiState.Loading)
    }

    @Test
    fun `无 accountId 路由参数时 isSelfProfile 为 true`() = runTest(testDispatcher) {
        // relaxed mock SavedStateHandle.get<String?>("accountId") 返回 null
        // → targetAccountId = AccountIds.USER_SELF_ID → isSelfProfile = true
        val viewModel = ProfileViewModel(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertTrue(viewModel.isSelfProfile)
    }
}
