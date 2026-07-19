package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.AccountIds
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
import com.trae.social.designsystem.image.SvgImageLoader
import coil.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    @SvgImageLoader val imageLoader: ImageLoader,
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
            // 主 review 第 2 轮修复：原 runCatching 会吞 CancellationException。
            // 主 review 第 6 轮修复：catch (Throwable) → catch (Exception) 让 Error（OOM 等）
            // 自然传播，避免 OOM 后还继续覆盖 UI 状态加剧崩溃，与同模块 ApiKeyViewModel /
            // FollowListViewModel 策略一致。本文件 8 处 catch 同步统一。
            val account = try {
                accountRepository.getById(SELF_ID)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "加载自身账号失败"); null
            }
            if (account == null) {
                _uiState.value = ProfileUiState.Empty
                return@launch
            }
            val following = try {
                followRelationDao.countFollowing(SELF_ID)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "加载关注数失败"); 0
            }
            val followers = try {
                followRelationDao.countFollowers(SELF_ID)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "加载粉丝数失败"); 0
            }
            _uiState.value = ProfileUiState.Success(
                account = account,
                followingCount = following,
                followersCount = followers,
            )
        }
    }

    private fun loadActivityLevel() {
        viewModelScope.launch {
            // 主 review 第 2 轮修复：原 runCatching 会吞 CancellationException。
            val level = try {
                configRepository.getAiActivityLevel()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "加载活跃度档位失败"); AiActivityLevel.MEDIUM
            }
            _activityLevel.value = level
        }
    }

    /**
     * 恢复已点赞状态。
     *
     * #103：此前按 likeCount > 0 启发式还原，但 likeCount 包含虚拟账号的点赞，
     * 导致未点赞推文被误标为已点赞。现在查询 interactions 表中当前用户的
     * LIKE 类型已执行互动记录，准确还原点赞状态。
     *
     * #140：竞态修复——若用户在 DB 查询返回前已手动切换点赞（userToggledLike=true），
     * 跳过整体覆盖，避免丢弃用户操作。
     */
    private fun loadInitialLikedTweetIds() {
        viewModelScope.launch {
            // 主 review 第 2 轮修复：原 runCatching 会吞 CancellationException。
            val likedIds = try {
                // #103：查询 interactions 表中当前用户已执行的 LIKE 互动，
                // 替代此前 likeCount > 0 的启发式判断
                interactionRepository.getLikedTweetIdsByAccount(SELF_ID)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "恢复已点赞状态失败")
                null
            }
            if (likedIds != null) {
                // #140：仅当用户尚未手动切换点赞时才用 DB 结果覆盖
                if (!userToggledLike) {
                    _likedTweetIds.value = likedIds.toSet()
                }
            }
        }
    }

    /**
     * 推文流（按作者 = 自身）。
     *
     * 作为 [mediaTweetsFlow] 的唯一上游——避免对同一 Room 查询
     * `observeByAuthor(SELF_ID)` 建立第二份独立 StateFlow 订阅（#225）。
     */
    val tweetsFlow: StateFlow<List<TweetEntity>> = tweetRepository.observeByAuthor(SELF_ID)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 媒体推文流（自身的含图推文）。
     *
     * #225 修复：派生自 [tweetsFlow] 而非重新订阅 `observeByAuthor(SELF_ID)`，
     * 与 tweetsFlow 共享同一 Room 触发器订阅与 SQL 查询流。
     */
    val mediaTweetsFlow: StateFlow<List<TweetEntity>> = tweetsFlow
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
            // 主 review 第 2 轮修复：原 runCatching 会吞 CancellationException。
            try {
                configRepository.setAiActivityLevel(level)
                _activityLevel.value = level
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "切换活跃度档位失败")
            }
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
     *
     * 主 review 第 2 轮修复：
     * - **CancellationException 重抛**：原 runCatching 会吞 CancellationException，
     *   viewModelScope 取消时会把取消误判为点赞失败触发回滚，污染 StateFlow 值。
     *   改为 try/catch 显式重抛 CancellationException。
     * - **DELETE 侧对称保护**：原 M5 修复只检查 INSERT 侧 IGNORE 返回值，
     *   DELETE 侧 `deleteLikeInteraction` 返回 Unit 无法判断是否实际删除。
     *   当 _likedTweetIds 与 DB 不同步时（误包含某 tweetId 但 DB 中无 LIKE 记录），
     *   会无条件 updateLikeCount(-1) 导致计数错误甚至负数。改为先查 DB 是否有 LIKE 记录，
     *   无则跳过计数更新。
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
            try {
                // M7 修复：同步写入/删除 interactions 表的 LIKE 记录，
                // 使 #103 的 loadInitialLikedTweetIds 能正确恢复点赞状态
                //
                // 主 review 第 1 轮 M5 修复：scheduleInteraction 在 (tweetId,accountId,type)
                // 唯一索引冲突时返回 -1（IGNORE），表示 DB 中已存在该账号对此推文的 LIKE 记录。
                // 此时若继续 updateLikeCount(+1) 会重复计数（likeCount 在原 LIKE 写入时已 +1）。
                // 改为检查返回值：IGNORE 时跳过计数更新；本地乐观状态（已 +tweetId）与 DB
                // 现状一致，无需回滚。
                //
                // 主 review 第 2 轮修复：DELETE 侧对称保护——先查 DB 是否有 LIKE 记录，
                // 无则跳过计数更新，避免 _likedTweetIds 误包含某 tweetId 但 DB 无记录时
                // 无条件 -1 导致 likeCount 错误。
                val shouldUpdateCount = if (wasLiked) {
                    val existing = interactionRepository.getLikedTweetIdsByAccount(SELF_ID)
                        .contains(tweetId)
                    if (existing) {
                        interactionRepository.deleteLikeInteraction(tweetId, SELF_ID)
                        true
                    } else {
                        // DB 中无此 LIKE 记录，likeCount 未曾因此 +1，无需 -1
                        false
                    }
                } else {
                    val now = System.currentTimeMillis()
                    val insertedRowId = interactionRepository.scheduleInteraction(
                        InteractionEntity(
                            id = UUID.randomUUID().toString(),
                            tweetId = tweetId,
                            accountId = SELF_ID,
                            type = InteractionType.LIKE,
                            content = null,
                            createdAt = now,
                            scheduledAt = now,
                            executedAt = now,
                        )
                    )
                    insertedRowId != -1L
                }
                if (shouldUpdateCount) {
                    tweetRepository.updateLikeCount(tweetId, delta)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "更新点赞计数失败，回滚本地状态")
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
            // 主 review 第 2 轮修复：原 runCatching 会吞 CancellationException。
            try {
                val original = tweetRepository.getById(tweetId) ?: return@launch
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "转发失败")
            }
        }
    }

    companion object {
        // #220：自身账号 ID 已抽到 AccountIds.USER_SELF_ID，此处保留别名仅向后兼容
        // （FollowListViewModel 等仍引用 ProfileViewModel.SELF_ID），新代码应直接引用 AccountIds
        const val SELF_ID = AccountIds.USER_SELF_ID
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
