package com.trae.social.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.map
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.InteractionType
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.repository.AccountRepository
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
    @FeedImageLoader val imageLoader: ImageLoader,
) : ViewModel() {

    /** 账号信息内存缓存，避免分页滚动时重复查库（key = authorId） */
    private val authorCache = mutableMapOf<String, AuthorInfo>()

    /** 已点赞推文 ID 集合（乐观更新） */
    private val _likedTweetIds = MutableStateFlow<Set<String>>(emptySet())
    val likedTweetIds: StateFlow<Set<String>> = _likedTweetIds.asStateFlow()

    /** 已收藏推文 ID 集合 */
    private val _bookmarkedTweetIds = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedTweetIds: StateFlow<Set<String>> = _bookmarkedTweetIds.asStateFlow()

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
     * 评论：写入评论计数 + 排程 COMMENT 互动。
     *
     * 评论内容当前仅记录到 InteractionRepository，未单独建表存储评论列表。
     */
    fun commentTweet(tweetId: String, authorId: String, text: String) {
        viewModelScope.launch {
            try {
                tweetRepository.updateCommentCount(tweetId, 1)
                interactionRepository.scheduleInteraction(
                    InteractionEntity(
                        id = UUID.randomUUID().toString(),
                        tweetId = tweetId,
                        accountId = authorId,
                        type = InteractionType.COMMENT,
                        content = text,
                        createdAt = System.currentTimeMillis(),
                        scheduledAt = System.currentTimeMillis(),
                        executedAt = System.currentTimeMillis(),
                    )
                )
            } catch (t: Throwable) {
                Timber.e(t, "评论失败")
            }
        }
    }

    /**
     * 转发：写入新推文（引用原推）+ 原推转发计数 +1 + 排程 RETWEET。
     */
    fun retweetTweet(original: TweetEntity) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val retweet = TweetEntity(
                    id = UUID.randomUUID().toString(),
                    authorId = original.authorId,
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
                        accountId = original.authorId,
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
}
