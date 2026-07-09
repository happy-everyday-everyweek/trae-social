package com.trae.social.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.map
import com.trae.social.core.data.entity.CommentEntity
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.InteractionType
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.CommentRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.InteractionRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.feed.di.FeedImageLoader
import coil.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * 信息流 UI 状态。
 */
sealed interface FeedUiState {
    /** 加载中（首次加载） */
    data object Loading : FeedUiState
    /** 加载成功，有数据 */
    data object Success : FeedUiState
    /** 加载失败 */
    data class Error(val message: String) : FeedUiState
    /** 空状态 */
    data object Empty : FeedUiState
}

/**
 * 信息流 ViewModel。
 *
 * 职责：
 * 1. 通过 Paging 3 暴露 [feedFlow]，逐条 join 账号信息得到 [TweetWithAuthor]
 * 2. 维护 [uiState] 驱动 Loading / Empty / Error 视图
 * 3. 维护点赞 / 收藏的本地乐观状态集合
 * 4. 提供刷新、点赞、评论、转发、收藏操作
 */
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val tweetRepository: TweetRepository,
    private val accountRepository: AccountRepository,
    private val interactionRepository: InteractionRepository,
    private val commentRepository: CommentRepository,
    private val configRepository: ConfigRepository,
    @FeedImageLoader val imageLoader: ImageLoader,
) : ViewModel() {

    /** 账号信息内存缓存，避免分页滚动时重复查库（key = authorId）。
     *  P2 修复：使用 ConcurrentHashMap 保证线程安全，避免 Paging 后台线程并发访问导致 ConcurrentModificationException。 */
    private val authorCache = java.util.concurrent.ConcurrentHashMap<String, AuthorInfo>()

    /** 已点赞推文 ID 集合（乐观更新） */
    private val _likedTweetIds = MutableStateFlow<Set<String>>(emptySet())
    val likedTweetIds: StateFlow<Set<String>> = _likedTweetIds.asStateFlow()

    /** 已收藏推文 ID 集合 */
    private val _bookmarkedTweetIds = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedTweetIds: StateFlow<Set<String>> = _bookmarkedTweetIds.asStateFlow()

    /** IMPL-13：是否跳过引导，FeedScreen 据此展示补全配置 banner */
    private val _isOnboardingSkipped = MutableStateFlow(false)
    val isOnboardingSkipped: StateFlow<Boolean> = _isOnboardingSkipped.asStateFlow()

    /** UI 状态 */
    private val _uiState = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    /**
     * 信息流分页数据流。
     *
     * TweetEntity → TweetWithAuthor：在 map 中逐条查账号（带内存缓存），
     * 缺失账号时回退为占位名，避免阻塞分页。
     */
    val feedFlow: Flow<PagingData<TweetWithAuthor>> = tweetRepository.getFeedFlow()
        .map { pagingData ->
            pagingData.map { tweet -> resolveAuthor(tweet) }
        }

    init {
        _uiState.value = FeedUiState.Loading
        // IMPL-13：读取跳过引导标记，驱动 FeedScreen 顶部 banner
        viewModelScope.launch {
            _isOnboardingSkipped.value = configRepository.isOnboardingSkipped()
        }
    }

    /**
     * 刷新：重置 UI 状态为 Loading。
     *
     * 实际分页数据刷新由 UI 层调用 `LazyPagingItems.refresh()` 触发，
     * 此处仅重置状态以显示 Loading 占位。
     */
    fun refresh() {
        _uiState.value = FeedUiState.Loading
    }

    /**
     * 通知 UI 层当前数据是否为空（由 Screen 收集 PagingItems 后回调）。
     */
    fun onEmptyResult() {
        _uiState.value = FeedUiState.Empty
    }

    fun onNonEmptyResult() {
        if (_uiState.value is FeedUiState.Loading || _uiState.value is FeedUiState.Error) {
            _uiState.value = FeedUiState.Success
        }
    }

    fun onError(message: String) {
        _uiState.value = FeedUiState.Error(message)
    }

    /**
     * 点赞：乐观更新本地集合，后台调 InteractionRepository 排程 + 更新计数。
     */
    fun likeTweet(tweetId: String, authorId: String) {
        val wasLiked = tweetId in _likedTweetIds.value
        val newSet = _likedTweetIds.value.toMutableSet()
        if (wasLiked) {
            newSet.remove(tweetId)
        } else {
            newSet.add(tweetId)
        }
        _likedTweetIds.value = newSet

        viewModelScope.launch {
            try {
                val delta = if (wasLiked) -1 else 1
                tweetRepository.updateLikeCount(tweetId, delta)
                interactionRepository.scheduleInteraction(
                    InteractionEntity(
                        id = UUID.randomUUID().toString(),
                        tweetId = tweetId,
                        accountId = authorId,
                        type = InteractionType.LIKE,
                        content = null,
                        createdAt = System.currentTimeMillis(),
                        scheduledAt = System.currentTimeMillis(),
                        executedAt = System.currentTimeMillis(),
                    )
                )
            } catch (t: Throwable) {
                Timber.e(t, "点赞失败，回滚本地状态")
                // 回滚
                val rollback = _likedTweetIds.value.toMutableSet()
                if (wasLiked) rollback.add(tweetId) else rollback.remove(tweetId)
                _likedTweetIds.value = rollback
            }
        }
    }

    /**
     * 评论：写入评论计数 + 排程 COMMENT 互动 + 持久化评论到 comments 表。
     *
     * 评论内容同时写入独立 comments 表（供 [loadComments] 加载展示），
     * 与 InteractionEntity(COMMENT) 的排程/审计记录并存：
     * - InteractionEntity 受 (tweetId,accountId,type) 唯一索引约束，每用户每推文仅一条；
     * - comments 表无此约束，支持同一用户对同一推文发表多条评论。
     */
    fun commentTweet(tweetId: String, authorId: String, text: String) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                tweetRepository.updateCommentCount(tweetId, 1)
                interactionRepository.scheduleInteraction(
                    InteractionEntity(
                        id = UUID.randomUUID().toString(),
                        tweetId = tweetId,
                        accountId = authorId,
                        type = InteractionType.COMMENT,
                        content = text,
                        createdAt = now,
                        scheduledAt = now,
                        executedAt = now,
                    )
                )
                commentRepository.addComment(
                    CommentEntity(
                        id = UUID.randomUUID().toString(),
                        tweetId = tweetId,
                        authorId = authorId,
                        content = text,
                        createdAt = now,
                    )
                )
            } catch (t: Throwable) {
                Timber.e(t, "评论失败")
            }
        }
    }

    /**
     * 加载某推文的持久化评论列表（评论弹层打开时调用）。
     *
     * 返回值按创建时间升序，作者信息（名/头像 seed）由 DAO JOIN accounts 带出；
     * 账号缺失时回退为占位名 / authorId 作为头像 seed，保证可展示。
     */
    suspend fun loadComments(tweetId: String): List<CommentItem> =
        runCatching {
            commentRepository.getCommentsForTweet(tweetId).map { c ->
                CommentItem(
                    id = c.id,
                    authorName = c.authorName ?: "未知用户",
                    authorAvatarSeed = c.authorAvatarSeed ?: c.authorId,
                    content = c.content,
                    createdAt = c.createdAt,
                )
            }
        }.onFailure { Timber.w(it, "加载评论失败") }.getOrDefault(emptyList())

    /**
     * 转发：写入新推文（引用原推）+ 原推转发计数 +1 + 排程 RETWEET。
     *
     * 转发推文的 authorId 为当前用户（user-self），而非原推作者。
     */
    fun retweetTweet(original: TweetEntity) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val retweet = TweetEntity(
                    id = UUID.randomUUID().toString(),
                    authorId = USER_SELF_ID,
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
                        accountId = USER_SELF_ID,
                        type = InteractionType.RETWEET,
                        content = null,
                        createdAt = now,
                        scheduledAt = now,
                        executedAt = now,
                    )
                )
            } catch (t: Throwable) {
                Timber.e(t, "转发失败")
            }
        }
    }

    /**
     * 收藏：切换本地收藏状态。
     */
    fun bookmarkTweet(tweetId: String) {
        val wasBookmarked = tweetId in _bookmarkedTweetIds.value
        val newSet = _bookmarkedTweetIds.value.toMutableSet()
        if (wasBookmarked) {
            newSet.remove(tweetId)
        } else {
            newSet.add(tweetId)
        }
        _bookmarkedTweetIds.value = newSet
    }

    /**
     * 解析推文作者信息（带内存缓存）。
     *
     * 查不到账号时回退为 "未知用户" / "unknown"，不阻塞分页。
     */
    private suspend fun resolveAuthor(tweet: TweetEntity): TweetWithAuthor {
        val author = authorCache.getOrPut(tweet.authorId) {
            val account = runCatching { accountRepository.getById(tweet.authorId) }.getOrNull()
            AuthorInfo(
                displayName = account?.displayName ?: "未知用户",
                username = account?.username ?: "unknown",
                avatarSeed = account?.avatarSeed ?: tweet.authorId
            )
        }
        return TweetWithAuthor(
            tweet = tweet,
            authorName = author.displayName,
            authorUsername = author.username,
            authorAvatarSeed = author.avatarSeed
        )
    }

    /** 作者信息缓存条目 */
    private data class AuthorInfo(
        val displayName: String,
        val username: String,
        val avatarSeed: String
    )

    private companion object {
        /** 当前用户账号 ID，与 PersonaSeeder.USER_SELF_ID / PublishViewModel.AUTHOR_SELF 一致 */
        const val USER_SELF_ID = "user-self"
    }
}
