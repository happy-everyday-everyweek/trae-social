package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.dao.FollowRelationDao
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.InteractionType
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.InteractionRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.profile.di.ProfileImageLoader
import coil.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    // #134：注入 InteractionRepository，使 retweetTweet 能创建实际互动记录
    private val interactionRepository: InteractionRepository,
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

    /**
     * #140：标记用户是否已手动切换过点赞。
     *
     * loadInitialLikedTweetIds 是异步的，若用户在 DB 查询返回前点击了 toggleLike，
     * 后续 DB 结果的整体覆盖会丢弃用户操作。此标记用于在 DB 结果返回时跳过覆盖。
     */
    @Volatile
    private var userToggledLike = false

    init {
        loadProfile()
        loadActivityLevel()
        loadInitialLikedTweetIds()
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
     * 恢复已点赞状态（#8 review）。
     *
     * DB 暂无 per-user 点赞记录，按 likeCount > 0 启发式还原：进入个人主页时，
     * 将 likeCount > 0 的自身推文视为已点赞。后续由 [toggleLike] 维护本地集合；
     * 理想方案需 DB 记录按用户点赞状态。
     *
     * #140：竞态修复——若用户在 DB 查询返回前已手动切换点赞（userToggledLike=true），
     * 跳过整体覆盖，避免丢弃用户操作。
     */
    private fun loadInitialLikedTweetIds() {
        viewModelScope.launch {
            runCatching { tweetRepository.observeByAuthor(SELF_ID).first() }
                .onSuccess { tweets ->
                    // #140：仅当用户尚未手动切换点赞时才用 DB 结果覆盖
                    if (!userToggledLike) {
                        _likedTweetIds.value = tweets
                            .filter { it.likeCount > 0 }
                            .map { it.id }
                            .toSet()
                    }
                }
                .onFailure { Timber.w(it, "恢复已点赞状态失败") }
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

    /**
     * #138：已点赞推文流（LIKES Tab 数据源）。
     *
     * 基于 [likedTweetIds] 动态切换查询，当点赞集合变化时自动刷新。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val likedTweetsFlow: StateFlow<List<TweetEntity>> = _likedTweetIds
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else tweetRepository.observeByIds(ids.toList())
        }
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
     *
     * 竞态修复：回滚前对比当前状态，仅在状态仍符合本次预期时回滚，
     * 避免快速“点赞 -> 取消”时第一次请求的回滚误将已取消的推文重新标记为已点赞。
     */
    fun toggleLike(tweetId: String) {
        val wasLiked = tweetId in _likedTweetIds.value
        // #140：标记用户已手动切换点赞，防止 loadInitialLikedTweetIds 的 DB 结果覆盖
        userToggledLike = true
        // 乐观更新本地集合
        _likedTweetIds.value = if (wasLiked) {
            _likedTweetIds.value - tweetId
        } else {
            _likedTweetIds.value + tweetId
        }
        viewModelScope.launch {
            val delta = if (wasLiked) -1 else 1
            runCatching { tweetRepository.updateLikeCount(tweetId, delta) }
                .onFailure {
                    Timber.w(it, "更新点赞计数失败，回滚本地状态")
                    // 仅在当前状态仍符合本次预期时回滚，避免与后续 toggle 竞态误覆盖
                    val currentState = _likedTweetIds.value
                    if (wasLiked && tweetId !in currentState) {
                        // 之前已点赞 -> 本次取消，回滚为已点赞
                        _likedTweetIds.value = currentState + tweetId
                    } else if (!wasLiked && tweetId in currentState) {
                        // 之前未点赞 -> 本次点赞，回滚为未点赞
                        _likedTweetIds.value = currentState - tweetId
                    }
                }
        }
    }

    /**
     * 评论（#8 / #134）：个人主页评论按钮。
     *
     * #134 修复：此前仅递增 commentCount 不创建评论数据，导致计数与实际脱节。
     * 现在不再递增计数（个人主页暂无评论输入弹层），保持计数与实际数据一致。
     * 后续接入评论弹层后，应创建 CommentEntity 并递增计数。
     */
    fun commentTweet(tweetId: String) {
        // #134：暂无评论输入弹层，不递增计数，避免计数与实际脱节
        Timber.i("个人主页评论按钮点击 tweetId=%s，暂未接入评论弹层", tweetId)
    }

    /**
     * 转发（#8 / #134）：创建转发推文副本 + 更新转发计数 + 排程 RETWEET 互动。
     *
     * #134 修复：此前仅递增 retweetCount 不创建转发推文，导致计数与实际脱节。
     * 现在创建实际的转发推文（引用原推文本），与 feature-feed 的 retweetTweet 一致。
     */
    fun retweetTweet(tweetId: String) {
        viewModelScope.launch {
            runCatching {
                val original = tweetRepository.getById(tweetId) ?: return@runCatching
                val now = System.currentTimeMillis()
                val retweet = TweetEntity(
                    id = UUID.randomUUID().toString(),
                    authorId = SELF_ID,
                    text = "转发：${original.text}",
                    mediaPath = original.mediaPath,
                    mediaTheme = original.mediaTheme,
                    createdAt = now,
                    likeCount = 0,
                    commentCount = 0,
                    retweetCount = 0,
                    isAiGenerated = false,
                    deduplicationKey = "retweet-${original.id}-$now"
                )
                tweetRepository.insertTweet(retweet)
                tweetRepository.updateRetweetCount(original.id, 1)
                interactionRepository.scheduleInteraction(
                    InteractionEntity(
                        id = UUID.randomUUID().toString(),
                        tweetId = original.id,
                        accountId = SELF_ID,
                        type = InteractionType.RETWEET,
                        content = null,
                        createdAt = now,
                        scheduledAt = now,
                        executedAt = now,
                    )
                )
            }.onFailure { Timber.w(it, "转发失败") }
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
