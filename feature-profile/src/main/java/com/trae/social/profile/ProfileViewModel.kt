package com.trae.social.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.AccountIds
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.InteractionType
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.FollowRelationRepository
import com.trae.social.core.data.repository.InteractionRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.designsystem.image.SvgImageLoader
import coil.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
 * 加载目标账号资料、推文/媒体列表与关注统计，读取当前 AI 活跃度档位。
 *
 * #11：通过 [SavedStateHandle] 读取路由参数 `accountId`，作为目标账号 ID。
 * - PROFILE Tab 路由无 `accountId` 参数，[targetAccountId] 回退为 [AccountIds.USER_SELF_ID]，
 *   行为等同原实现（显示自身账号）。
 * - ACCOUNT_DETAIL 路由携带 `accountId` 参数，[targetAccountId] 为该账号 ID，
 *   ProfileScreen 显示目标账号资料、推文、媒体；点赞/转发等交互仍以 [AccountIds.USER_SELF_ID]
 *   作为行为主体（当前登录用户），LIKES Tab 仅在查看自身时显示。
 *
 * #286：自身账号固定 ID 统一为 [AccountIds.USER_SELF_ID]（原 ProfileViewModel.SELF_ID /
 * PersonaSeeder.USER_SELF_ID / PublishViewModel.AUTHOR_SELF 别名已移除，全部直接引用 AccountIds）。
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val tweetRepository: TweetRepository,
    private val configRepository: ConfigRepository,
    // #314：改注入 FollowRelationRepository 替代直接注入 FollowRelationDao，
    // 遵循依赖倒置——ViewModel 不应感知数据层 DAO 实现
    private val followRelationRepository: FollowRelationRepository,
    // #134：注入 InteractionRepository，使 retweetTweet 能创建实际互动记录
    private val interactionRepository: InteractionRepository,
    // #11：注入 SavedStateHandle 读取 ACCOUNT_DETAIL 路由的 accountId 参数
    private val savedStateHandle: SavedStateHandle,
    @SvgImageLoader val imageLoader: ImageLoader,
) : ViewModel() {

    /**
     * #11：目标账号 ID。PROFILE Tab 路由无 accountId 参数时回退 [SELF_ID]，
     * ACCOUNT_DETAIL 路由携带 accountId 时使用该值，用于加载目标账号资料/推文/媒体/计数。
     */
    val targetAccountId: String = savedStateHandle
        .get<String?>(KEY_ACCOUNT_ID_ARG)
        ?: AccountIds.USER_SELF_ID

    /**
     * #11：是否查看自身账号。UI 据此决定标题、设置入口、推荐关注入口、LIKES Tab 的显隐。
     */
    val isSelfProfile: Boolean = targetAccountId == AccountIds.USER_SELF_ID

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
     * 待确认的乐观点赞增量：tweetId -> 乐观状态（true=已点赞，false=已取消）。
     *
     * review 修复（#166）：原 userToggledLike 布尔标记在 toggleLike 后永不复位，
     * 导致首次手动点赞后所有后续 DB Flow 排放被静默丢弃，LIKES Tab 不再实时刷新。
     *
     * 改为按 tweetId 维度记录待确认增量——DB Flow 排放反映权威状态，叠加尚未被
     * DB 确认的乐观增量后写入 _likedTweetIds。当 DB Flow 已包含（或不包含）某
     * tweetId 且与乐观状态一致时，该增量被确认并移除。
     *
     * 主 review 第 4 轮修复：原 `mutableMapOf` 为非线程安全 Map。toggleLike() 运行于
     * viewModelScope（Dispatchers.Main.immediate），但 Room Flow collector 默认并不
     * 保证在主线程发射（依赖 Repository/DAO 是否 flowOn）。使用 [ConcurrentHashMap]
     * 做防御性保护，避免后续若有人在 InteractionRepository 侧给 Flow 加 `.flowOn`
     * 之外的算子时触发 ConcurrentModificationException 或状态丢失。
     */
    private val pendingLikeToggles = ConcurrentHashMap<String, Boolean>()

    init {
        loadProfile()
        loadActivityLevel()
        observeLikedTweetIds()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            // review 第 5 轮修复：改用 observeById Flow 持续订阅目标账号资料，
            // PersonaUpdateWorker 更新人设后 ProfileScreen 头部自动刷新，
            // 与 TimelineViewModel.selfProfile 行为一致。原为一次性 getById，人设更新后头部不刷新。
            //
            // 主 review 第 6 轮修复：catch (Throwable) → catch (Exception) 让 Error（OOM 等）
            // 自然传播，避免 OOM 后还继续覆盖 UI 状态加剧崩溃，与同模块 ApiKeyViewModel /
            // FollowListViewModel 策略一致。
            //
            // #184：关注/粉丝计数改为 observe Flow（observeFollowingCount / observeFollowersCount），
            // 此处仅加载目标账号资料；计数刷新由 observeProfileCounts 处理，FollowListViewModel.toggleFollow
            // 写库后 ProfileViewModel 自动收到新计数。
            //
            // #11：加载 [targetAccountId] 对应账号资料（PROFILE Tab 路由下 == SELF_ID，
            // ACCOUNT_DETAIL 路由下为目标账号 ID），替换原硬编码 SELF_ID。
            var countsObserved = false
            try {
                accountRepository.observeById(targetAccountId).collect { account ->
                    if (account == null) {
                        _uiState.value = ProfileUiState.Empty
                        return@collect
                    }
                    if (!countsObserved) {
                        // 首次拿到账号：加载计数初值并启动 observe 持续刷新计数
                        countsObserved = true
                        val following = try {
                            followRelationRepository.countFollowing(targetAccountId)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "加载关注数失败"); 0
                        }
                        val followers = try {
                            followRelationRepository.countFollowers(targetAccountId)
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
                        // #184：启动 observe，后续 FollowListViewModel.toggleFollow 写库后自动刷新计数
                        observeProfileCounts()
                    } else {
                        // 后续 account 变更：复用 _uiState 中的最新计数（由 observeProfileCounts 维护），
                        // 仅替换 account，避免覆盖正在刷新的计数。
                        val current = _uiState.value
                        if (current is ProfileUiState.Success) {
                            _uiState.value = current.copy(account = account)
                        } else {
                            _uiState.value = ProfileUiState.Success(
                                account = account,
                                followingCount = 0,
                                followersCount = 0,
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "observe 账号资料失败 accountId=%s", targetAccountId)
                if (_uiState.value !is ProfileUiState.Success) {
                    _uiState.value = ProfileUiState.Empty
                }
            }
        }
    }

    /**
     * #184：observe 关注/粉丝计数，FollowListViewModel.toggleFollow 写库后自动刷新 ProfileUiState。
     *
     * 仅当 _uiState 已是 Success 时合并新计数，避免覆盖 Loading/Empty 状态。
     *
     * #11：observe [targetAccountId] 的计数（PROFILE Tab 路由下 == SELF_ID，
     * ACCOUNT_DETAIL 路由下为目标账号 ID）。
     */
    private fun observeProfileCounts() {
        viewModelScope.launch {
            try {
                followRelationRepository.observeFollowingCount(targetAccountId).collect { count ->
                    val current = _uiState.value
                    if (current is ProfileUiState.Success) {
                        _uiState.value = current.copy(followingCount = count)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "observe followingCount 失败")
            }
        }
        viewModelScope.launch {
            try {
                followRelationRepository.observeFollowersCount(targetAccountId).collect { count ->
                    val current = _uiState.value
                    if (current is ProfileUiState.Success) {
                        _uiState.value = current.copy(followersCount = count)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "observe followersCount 失败")
            }
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
     * 恢复并持续同步已点赞状态。
     *
     * #103：此前按 likeCount > 0 启发式还原，但 likeCount 包含虚拟账号的点赞，
     * 导致未点赞推文被误标为已点赞。现在查询 interactions 表中当前用户的
     * LIKE 类型已执行互动记录，准确还原点赞状态。
     *
     * #166：改为 observe Flow 持续订阅，FeedViewModel.likeTweet 写入 interactions 表后
     * ProfileViewModel._likedTweetIds 自动收到新值，LIKES Tab 实时刷新，无需杀进程重启。
     *
     * review 修复：DB Flow 排放为权威状态，叠加尚未被 DB 确认的 pendingLikeToggles
     * 乐观增量后写入 _likedTweetIds。这样既不会丢弃用户手动操作（乐观增量在 DB 写入
     * 完成前保护本地状态），也不会永久阻塞 Flow（DB 追上乐观状态后增量自动移除）。
     */
    private fun observeLikedTweetIds() {
        viewModelScope.launch {
            try {
                interactionRepository.observeLikedTweetIdsByAccount(AccountIds.USER_SELF_ID).collect { likedIds ->
                    val dbSet = likedIds.toSet()
                    val merged = dbSet.toMutableSet()
                    val iterator = pendingLikeToggles.entries.iterator()
                    while (iterator.hasNext()) {
                        val (tweetId, optimisticallyLiked) = iterator.next()
                        if (optimisticallyLiked == (tweetId in dbSet)) {
                            // DB 已追上乐观状态，增量确认，移除
                            iterator.remove()
                        } else if (optimisticallyLiked) {
                            // DB 尚未写入 LIKE，保留乐观增量
                            merged.add(tweetId)
                        } else {
                            // DB 尚未删除 LIKE，保留乐观取消
                            merged.remove(tweetId)
                        }
                    }
                    _likedTweetIds.value = merged
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "observe 已点赞状态失败")
            }
        }
    }

    /**
     * 推文流（按作者 = [targetAccountId]）。
     *
     * 作为 [mediaTweetsFlow] 的唯一上游——避免对同一 Room 查询
     * `observeByAuthor(targetAccountId)` 建立第二份独立 StateFlow 订阅（#225）。
     *
     * #11：观察目标账号的推文（PROFILE Tab 路由下 == SELF_ID，
     * ACCOUNT_DETAIL 路由下为目标账号 ID），原硬编码 SELF_ID 替换为 [targetAccountId]。
     */
    val tweetsFlow: StateFlow<List<TweetEntity>> = tweetRepository.observeByAuthor(targetAccountId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 媒体推文流（[targetAccountId] 的含图推文）。
     *
     * #225 修复：派生自 [tweetsFlow] 而非重新订阅 `observeByAuthor(targetAccountId)`，
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
        // review 修复：记录乐观增量，DB Flow 排放时叠加尚未确认的增量
        pendingLikeToggles[tweetId] = !wasLiked
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
                    // #316：单条 EXISTS 查询替代 getLikedTweetIdsByAccount 全量加载——
                    // 后者拉取当前账号全部已点赞推文 ID 列表只为 .contains 检查单个 ID，
                    // N 条 LIKE 即拉取 N 行；EXISTS 短路返回，开销 O(log N)。
                    val existing = interactionRepository.hasLikeInteraction(tweetId, AccountIds.USER_SELF_ID)
                    if (existing) {
                        interactionRepository.deleteLikeInteraction(tweetId, AccountIds.USER_SELF_ID)
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
                            accountId = AccountIds.USER_SELF_ID,
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
                // review 修复：DB 写入失败，移除待确认乐观增量，让后续 DB Flow 排放
                // 能正常覆盖（不再被乐观增量保护）
                pendingLikeToggles.remove(tweetId)
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
                    authorId = AccountIds.USER_SELF_ID,
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
                        accountId = AccountIds.USER_SELF_ID,
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
        // #11：ACCOUNT_DETAIL 路由参数键，与 AppRoutes.ACCOUNT_DETAIL_ID_ARG 保持一致。
        // 此处不依赖 app 模块（feature-profile 不能反向依赖 app），直接用字符串常量对齐。
        // SavedStateHandle 通过此键读取 navArgument("accountId") 注入的值。
        const val KEY_ACCOUNT_ID_ARG = "accountId"
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
