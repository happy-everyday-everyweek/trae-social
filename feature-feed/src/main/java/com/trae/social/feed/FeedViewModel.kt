package com.trae.social.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
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
import com.trae.social.core.profiling.capture.SessionManager
import com.trae.social.core.profiling.capture.UserActionEventBuilder
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.profiling.feedback.FeedbackController
import com.trae.social.core.profiling.feedback.UserProfileReadAccess
import com.trae.social.feed.di.FeedImageLoader
import coil.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * 信息流 ViewModel。
 *
 * 职责：
 * 1. 通过 Paging 3 暴露 [feedFlow]，逐条 join 账号信息得到 [TweetWithAuthor]
 * 2. 维护点赞 / 收藏 / 不感兴趣的本地乐观状态集合（收藏与不感兴趣持久化到 DataStore）
 * 3. 提供点赞、评论、转发、收藏、不感兴趣操作
 *
 * #135：移除了未被 FeedScreen 消费的 FeedUiState 死状态。
 * FeedScreen 的 Loading/Error/Empty/List 判断全部基于 pagingItems.loadState。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val tweetRepository: TweetRepository,
    private val accountRepository: AccountRepository,
    private val interactionRepository: InteractionRepository,
    private val commentRepository: CommentRepository,
    private val configRepository: ConfigRepository,
    private val userActionTracker: UserActionTracker,
    private val sessionManager: SessionManager,
    // #146 A/E 场景 5（feedBoost）：信息流读侧消费用户画像，暴露兴趣供 UI 展示"为你推荐"标签
    private val readAccess: UserProfileReadAccess,
    private val feedbackController: FeedbackController,
    @FeedImageLoader val imageLoader: ImageLoader,
) : ViewModel() {

    /** #146 B：信息流交互埋点构建器（session 由 SessionManager 提供，冷启动兜底 "unknown"）。 */
    private val actionBuilder = UserActionEventBuilder(userActionTracker) {
        sessionManager.currentSessionId() ?: "unknown"
    }

    /** #146 A/E 场景 5：当前激活画像驱动的信息流 boost 是否生效（UI 据此显示"根据你的兴趣优化"标签）。 */
    private val _feedBoostEnabled = MutableStateFlow(false)
    val feedBoostEnabled: StateFlow<Boolean> = _feedBoostEnabled.asStateFlow()

    /** #146 E：用户兴趣画像（top 主题），供 UI 展示兴趣标签；画像为空时返回空。 */
    private val _profileInterests = MutableStateFlow<List<String>>(emptyList())
    val profileInterests: StateFlow<List<String>> = _profileInterests.asStateFlow()

    /** 账号信息内存缓存，避免分页滚动时重复查库（key = authorId）。
     *  P2 修复：使用 ConcurrentHashMap 保证线程安全，避免 Paging 后台线程并发访问导致 ConcurrentModificationException。
     *  #141：缓存条目带时间戳，超过 [AUTHOR_CACHE_TTL_MS] 后自动失效重新查库，
     *  确保 PersonaUpdateWorker 更新人设后 Feed 能刷新到最新作者信息。 */
    private val authorCache = java.util.concurrent.ConcurrentHashMap<String, CachedAuthor>()

    /** 已点赞推文 ID 集合（乐观更新） */
    private val _likedTweetIds = MutableStateFlow<Set<String>>(emptySet())
    val likedTweetIds: StateFlow<Set<String>> = _likedTweetIds.asStateFlow()

    /** 已收藏推文 ID 集合（#102：持久化到 DataStore，重启不丢失） */
    private val _bookmarkedTweetIds = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedTweetIds: StateFlow<Set<String>> = _bookmarkedTweetIds.asStateFlow()

    /** #142：不感兴趣的推文 ID 集合（持久化到 DataStore，从信息流过滤） */
    private val _notInterestedTweetIds = MutableStateFlow<Set<String>>(emptySet())
    val notInterestedTweetIds: StateFlow<Set<String>> = _notInterestedTweetIds.asStateFlow()

    /** IMPL-13：是否跳过引导，FeedScreen 据此展示补全配置 banner */
    private val _isOnboardingSkipped = MutableStateFlow(false)
    val isOnboardingSkipped: StateFlow<Boolean> = _isOnboardingSkipped.asStateFlow()

    /**
     * 信息流分页数据流。
     *
     * TweetEntity → TweetWithAuthor：在 map 中逐条查账号（带内存缓存），
     * 缺失账号时回退为占位名，避免阻塞分页。
     * #142：过滤掉用户标记为"不感兴趣"的推文。
     * UI-fix：使用 flatMapLatest 组合 _notInterestedTweetIds，点击"不感兴趣"后自动重新过滤，
     * 无需用户手动下拉刷新；cachedIn 保持配置变更后的分页状态。
     */
    val feedFlow: Flow<PagingData<TweetWithAuthor>> = _notInterestedTweetIds
        .flatMapLatest { hidden ->
            tweetRepository.getFeedFlow().map { pagingData ->
                pagingData
                    .filter { it.id !in hidden }
                    .map { tweet -> resolveAuthor(tweet) }
            }
        }
        .cachedIn(viewModelScope)

    init {
        // IMPL-13：读取跳过引导标记，驱动 FeedScreen 顶部 banner
        viewModelScope.launch {
            _isOnboardingSkipped.value = configRepository.isOnboardingSkipped()
        }
        // #146 A/E 场景 5：读取画像反哺权重与兴趣向量，驱动信息流 boost 标签与兴趣展示。
        // feedBoost 实际重排受 Paging 分页语义约束（重排破坏分页），此处先打通读侧消费，
        // 暴露 boostEnabled / profileInterests 供 UI 展示；后续可结合 RemoteMediator 做服务端 boost。
        viewModelScope.launch {
            _feedBoostEnabled.value = feedbackController.shouldApply(5)
            _profileInterests.value = readAccess.interestVector()
                .entries.sortedByDescending { it.value }.take(8).map { it.key }
        }
        // #102：启动时从 DataStore 恢复收藏状态
        viewModelScope.launch {
            runCatching { configRepository.getBookmarkedTweetIds() }
                .onSuccess { restored ->
                    // m6 修复：仅在用户尚未操作过时才用恢复值覆盖，避免恢复协程覆盖用户操作
                    if (_bookmarkedTweetIds.value.isEmpty()) {
                        _bookmarkedTweetIds.value = restored
                    }
                }
                .onFailure { Timber.w(it, "恢复收藏状态失败") }
        }
        // #142：启动时从 DataStore 恢复不感兴趣状态
        viewModelScope.launch {
            runCatching { configRepository.getNotInterestedTweetIds() }
                .onSuccess { restored ->
                    // m6 修复：仅在用户尚未操作过时才用恢复值覆盖，避免恢复协程覆盖用户操作
                    if (_notInterestedTweetIds.value.isEmpty()) {
                        _notInterestedTweetIds.value = restored
                    }
                }
                .onFailure { Timber.w(it, "恢复不感兴趣状态失败") }
        }
    }

    /**
     * 点赞：乐观更新本地集合，后台调 InteractionRepository 排程 + 更新计数。
     */
    fun likeTweet(tweetId: String) {
        val wasLiked = tweetId in _likedTweetIds.value
        val newSet = _likedTweetIds.value.toMutableSet()
        if (wasLiked) {
            newSet.remove(tweetId)
        } else {
            newSet.add(tweetId)
        }
        _likedTweetIds.value = newSet
        // #146 B：点赞/取消点赞埋点（驱动第二层基础分析的互动率统计）
        actionBuilder.emit(
            type = if (wasLiked) UserActionType.TWEET_UNLIKE else UserActionType.TWEET_LIKE,
            screen = "feed",
            targetId = tweetId,
            targetKind = "tweet",
        )

        viewModelScope.launch {
            try {
                val delta = if (wasLiked) -1 else 1
                tweetRepository.updateLikeCount(tweetId, delta)
                interactionRepository.scheduleInteraction(
                    InteractionEntity(
                        id = UUID.randomUUID().toString(),
                        tweetId = tweetId,
                        // #132：accountId 应为执行互动的当前用户，而非推文作者
                        accountId = USER_SELF_ID,
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
    fun commentTweet(tweetId: String, text: String) {
        // #146 B：评论埋点（extra 带评论字数，供画像分析评论偏好）
        actionBuilder.emit(
            type = UserActionType.TWEET_COMMENT,
            screen = "feed",
            targetId = tweetId,
            targetKind = "tweet",
            extra = mapOf("commentLen" to kotlinx.serialization.json.JsonPrimitive(text.length)),
        )
        viewModelScope.launch {
            var interactionInserted = false
            try {
                val now = System.currentTimeMillis()
                tweetRepository.updateCommentCount(tweetId, 1)
                interactionRepository.scheduleInteraction(
                    InteractionEntity(
                        id = UUID.randomUUID().toString(),
                        tweetId = tweetId,
                        // #133：accountId 应为发表评论的当前用户，而非推文作者
                        accountId = USER_SELF_ID,
                        type = InteractionType.COMMENT,
                        content = text,
                        createdAt = now,
                        scheduledAt = now,
                        executedAt = now,
                    )
                )
                interactionInserted = true
                commentRepository.addComment(
                    CommentEntity(
                        id = UUID.randomUUID().toString(),
                        tweetId = tweetId,
                        // #133：评论作者为当前用户，与 CommentSheet 乐观展示的"我"一致
                        authorId = USER_SELF_ID,
                        content = text,
                        createdAt = now,
                    )
                )
            } catch (t: Throwable) {
                Timber.e(t, "评论失败，回滚 commentCount 并清理孤儿 interaction")
                // #139：步骤 1（updateCommentCount）已提交到 DB，若步骤 2/3 失败需回滚计数，
                // 避免计数漂移累积（对比 likeTweet 失败时回滚 _likedTweetIds）
                runCatching { tweetRepository.updateCommentCount(tweetId, -1) }
                    .onFailure { Timber.w(it, "回滚 commentCount 失败") }
                // m7 修复：若 COMMENT interaction 已写入但 addComment 失败，删除孤儿 interaction
                if (interactionInserted) {
                    runCatching { interactionRepository.deleteCommentInteraction(tweetId, USER_SELF_ID) }
                        .onFailure { Timber.w(it, "清理孤儿 COMMENT interaction 失败") }
                }
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
        // #146 B：转发埋点
        actionBuilder.emit(
            type = UserActionType.TWEET_RETWEET,
            screen = "feed",
            targetId = original.id,
            targetKind = "tweet",
        )
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
     * 收藏：切换本地收藏状态并持久化到 DataStore（#102：重启不丢失）。
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
        // #146 B：收藏/取消收藏埋点
        actionBuilder.emit(
            type = if (wasBookmarked) UserActionType.TWEET_UNBOOKMARK else UserActionType.TWEET_BOOKMARK,
            screen = "feed",
            targetId = tweetId,
            targetKind = "tweet",
        )
        // #102：持久化到 DataStore，确保旋转屏幕或杀进程重启后收藏状态不丢失
        viewModelScope.launch {
            runCatching { configRepository.setBookmarkedTweetIds(newSet) }
                .onFailure { Timber.w(it, "持久化收藏状态失败") }
        }
    }

    /**
     * 不感兴趣：将推文加入隐藏集合并持久化（#142）。
     *
     * 该推文将从信息流中过滤，且重启后仍保持隐藏。
     */
    fun markNotInterested(tweetId: String) {
        val newSet = _notInterestedTweetIds.value + tweetId
        _notInterestedTweetIds.value = newSet
        viewModelScope.launch {
            runCatching { configRepository.setNotInterestedTweetIds(newSet) }
                .onFailure { Timber.w(it, "持久化不感兴趣状态失败") }
        }
    }

    /**
     * 解析推文作者信息（带 TTL 内存缓存）。
     *
     * 查不到账号时回退为 "未知用户" / "unknown"，不阻塞分页。
     * #141：缓存条目超过 [AUTHOR_CACHE_TTL_MS] 后自动失效，重新查库获取最新人设信息。
     */
    private suspend fun resolveAuthor(tweet: TweetEntity): TweetWithAuthor {
        val now = System.currentTimeMillis()
        val cached = authorCache[tweet.authorId]
        val author = if (cached != null && now - cached.cachedAt < AUTHOR_CACHE_TTL_MS) {
            cached.info
        } else {
            val account = runCatching { accountRepository.getById(tweet.authorId) }.getOrNull()
            val info = AuthorInfo(
                displayName = account?.displayName ?: "未知用户",
                username = account?.username ?: "unknown",
                avatarSeed = account?.avatarSeed ?: tweet.authorId
            )
            authorCache[tweet.authorId] = CachedAuthor(info = info, cachedAt = now)
            info
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

    /** #141：带时间戳的缓存条目，用于 TTL 判断 */
    private data class CachedAuthor(
        val info: AuthorInfo,
        val cachedAt: Long,
    )

    private companion object {
        /** 当前用户账号 ID，与 PersonaSeeder.USER_SELF_ID / PublishViewModel.AUTHOR_SELF 一致 */
        const val USER_SELF_ID = "user-self"
        /** #141：作者缓存 TTL（5 分钟），超时后重新查库刷新人设信息 */
        const val AUTHOR_CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
