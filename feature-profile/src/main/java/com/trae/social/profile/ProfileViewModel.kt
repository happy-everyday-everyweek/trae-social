package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.dao.FollowRelationDao
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.profile.di.ProfileImageLoader
import coil.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 个人主页 ViewModel（IMPL-2）。
 *
 * 加载自身账号资料、推文/媒体列表与关注统计，读取当前 AI 活跃度档位。
 * 自身账号固定 ID 为 [SELF_ID]（与 PersonaSeeder.USER_SELF_ID / PublishViewModel.AUTHOR_SELF 一致）。
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val tweetRepository: TweetRepository,
    private val configRepository: ConfigRepository,
    private val followRelationDao: FollowRelationDao,
    @ProfileImageLoader val imageLoader: ImageLoader,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _activityLevel = MutableStateFlow(AiActivityLevel.MEDIUM)
    val activityLevel: StateFlow<AiActivityLevel> = _activityLevel.asStateFlow()

    private val _selectedTab = MutableStateFlow(ProfileTab.TWEETS)
    val selectedTab: StateFlow<ProfileTab> = _selectedTab.asStateFlow()

    /** 已点赞推文 ID 集合（乐观更新，与 feature-feed FeedViewModel 一致） */
    private val _likedTweetIds = MutableStateFlow<Set<String>>(emptySet())
    val likedTweetIds: StateFlow<Set<String>> = _likedTweetIds.asStateFlow()

    init {
        loadProfile()
        loadActivityLevel()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            val account = runCatching { accountRepository.getById(SELF_ID) }
                .getOrElse { Timber.w(it, "加载自身账号失败"); null }
            if (account == null) {
                _uiState.value = ProfileUiState.Empty
                return@launch
            }
            val following = runCatching { followRelationDao.countFollowing(SELF_ID) }.getOrElse { 0 }
            val followers = runCatching { followRelationDao.countFollowers(SELF_ID) }.getOrElse { 0 }
            _uiState.value = ProfileUiState.Success(
                account = account,
                followingCount = following,
                followersCount = followers,
            )
        }
    }

    private fun loadActivityLevel() {
        viewModelScope.launch {
            _activityLevel.value = runCatching { configRepository.getAiActivityLevel() }
                .getOrElse { AiActivityLevel.MEDIUM }
        }
    }

    /**
     * 推文流（按作者 = 自身）。
     */
    val tweetsFlow: StateFlow<List<TweetEntity>> = tweetRepository.observeByAuthor(SELF_ID)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 媒体推文流（自身的含图推文）。
     */
    val mediaTweetsFlow: StateFlow<List<TweetEntity>> = tweetRepository.observeByAuthor(SELF_ID)
        .map { list -> list.filter { !it.mediaPath.isNullOrBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectTab(tab: ProfileTab) {
        _selectedTab.value = tab
    }

    fun setActivityLevel(level: AiActivityLevel) {
        viewModelScope.launch {
            runCatching { configRepository.setAiActivityLevel(level) }
                .onSuccess { _activityLevel.value = level }
                .onFailure { Timber.w(it, "切换活跃度档位失败") }
        }
    }

    /**
     * 切换点赞状态（#8）：乐观更新本地集合，后台持久化 likeCount。
     *
     * 与 feature-feed 一致：DB likeCount 为计数唯一数据源，乐观更新通过
     * [TweetRepository.updateLikeCount] 写入，Room 重发后 [tweetsFlow] 携带新计数。
     */
    fun toggleLike(tweetId: String) {
        val wasLiked = tweetId in _likedTweetIds.value
        val newSet = _likedTweetIds.value.toMutableSet()
        if (wasLiked) newSet.remove(tweetId) else newSet.add(tweetId)
        _likedTweetIds.value = newSet
        viewModelScope.launch {
            val delta = if (wasLiked) -1 else 1
            runCatching { tweetRepository.updateLikeCount(tweetId, delta) }
                .onFailure {
                    Timber.w(it, "更新点赞计数失败，回滚本地状态")
                    val rollback = _likedTweetIds.value.toMutableSet()
                    if (wasLiked) rollback.add(tweetId) else rollback.remove(tweetId)
                    _likedTweetIds.value = rollback
                }
        }
    }

    /**
     * 评论（#8）：评论计数 +1 持久化。
     *
     * 注：个人主页暂未接入评论弹层与 CommentRepository（避免引入跨 feature 依赖），
     * 此处仅持久化计数，提供与信息流一致的互动反馈。
     */
    fun commentTweet(tweetId: String) {
        viewModelScope.launch {
            runCatching { tweetRepository.updateCommentCount(tweetId, 1) }
                .onFailure { Timber.w(it, "更新评论计数失败") }
        }
    }

    /**
     * 转发（#8）：转发计数 +1 持久化。
     *
     * 注：暂不创建转发推文副本与排程互动（避免引入 InteractionRepository 依赖），
     * 仅持久化计数，保持互动按钮可用。
     */
    fun retweetTweet(tweetId: String) {
        viewModelScope.launch {
            runCatching { tweetRepository.updateRetweetCount(tweetId, 1) }
                .onFailure { Timber.w(it, "更新转发计数失败") }
        }
    }

    companion object {
        /** 自身账号固定 ID（与 PersonaSeeder.USER_SELF_ID 一致）。 */
        const val SELF_ID = "user-self"
    }
}

/** 个人主页 UI 状态。 */
sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data object Empty : ProfileUiState
    data class Success(
        val account: AccountEntity,
        val followingCount: Int,
        val followersCount: Int,
    ) : ProfileUiState
}

/** 个人主页 Tab。 */
enum class ProfileTab(val label: String) {
    TWEETS("推文"),
    MEDIA("媒体"),
    LIKES("喜欢"),
}
