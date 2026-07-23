package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.AccountIds
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.FollowRelationEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.FollowRelationRepository
import com.trae.social.core.data.model.ScenarioIds
import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.profiling.capture.SessionManager
import com.trae.social.core.profiling.capture.UserActionEventBuilder
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.core.profiling.feedback.FeedbackController
import com.trae.social.core.profiling.feedback.UserProfileReadAccess
import com.trae.social.designsystem.image.SvgImageLoader
import coil.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 关注/粉丝/推荐关注列表 ViewModel（IMPL-2 + #146 A/E 场景 6）。
 *
 * 根据 [FollowListType] 拉取列表：
 * - FOLLOWING / FOLLOWERS：从关注关系表 join 出账号资料；
 * - RECOMMENDED（#146 场景 6 followRecommend）：driven 组按用户兴趣向量对虚拟账号打分推荐，
 *   control 组随机推荐，供 computeFeedbackEffect 做 A/B 回测关注转化率 delta。
 */
@HiltViewModel
class FollowListViewModel @Inject constructor(
    // #314：改注入 FollowRelationRepository 替代直接注入 FollowRelationDao，
    // 遵循依赖倒置——ViewModel 不应感知数据层 DAO 实现
    private val followRelationRepository: FollowRelationRepository,
    private val accountRepository: AccountRepository,
    private val userActionTracker: UserActionTracker,
    private val sessionManager: SessionManager,
    // #146 A/E 场景 6 followRecommend
    private val feedbackController: FeedbackController,
    private val readAccess: UserProfileReadAccess,
    @SvgImageLoader val imageLoader: ImageLoader,
) : ViewModel() {

    /** #146 B：关注/取关埋点构建器。 */
    private val actionBuilder = UserActionEventBuilder(userActionTracker) {
        sessionManager.currentSessionId() ?: "unknown"
    }

    private val _uiState = MutableStateFlow<FollowListUiState>(FollowListUiState.Loading)
    val uiState: StateFlow<FollowListUiState> = _uiState.asStateFlow()

    /** 当前用户已关注的账号 ID 集合，用于驱动列表项"关注/已关注"按钮状态。 */
    private val _followingIds = MutableStateFlow<Set<String>>(emptySet())
    val followingIds: StateFlow<Set<String>> = _followingIds.asStateFlow()

    /**
     * in-flight toggle 去重集合：记录 DB 写盘尚未完成的 accountId，
     * 阻止同一账号在 DB 写完成前重复 toggle。toggleFollow 在主线程同步 add，
     * DB 写完成后在 finally 中 remove；viewModelScope 默认 Dispatchers.Main.immediate，无需额外同步。
     */
    private val inflightToggles: MutableSet<String> = mutableSetOf()

    /**
     * #146 A/E 场景 6：最近一次 RECOMMENDED 列表的 driven 分组结果。
     * toggleFollow 落 FOLLOW 埏点时带上 scenarioId=6 / drivenByProfile / group，
     * 使 computeScenarioStats 能把"真实用户关注"计入互动分子。
     */
    private var scenario6Driven: Boolean? = null

    fun load(type: FollowListType) {
        viewModelScope.launch {
            // 并发保护：有 in-flight toggle 时跳过 load，避免 DB 查询覆盖 toggleFollow 的乐观更新。
            if (inflightToggles.isNotEmpty()) {
                Timber.d("load 跳过：有 inflight toggle=%s，避免覆盖乐观更新", inflightToggles)
                return@launch
            }
            val prevUiState = _uiState.value
            _uiState.value = FollowListUiState.Loading
            // FOLLOWING 分支单次查询：ids 和 accounts 复用同一 relations 结果，保证一致性。
            // #315：accounts 改用 JOIN 一次取回，替代 N 次 getById（N = 关注/粉丝数）。
            // _followingIds 仍由 getFollowing 单独查，因 FOLLOWERS / RECOMMENDED 列表也需驱动按钮状态。
            val (dbFollowingIds, accounts) = try {
                val followingRelations = followRelationRepository.getFollowing(AccountIds.USER_SELF_ID)
                val ids = followingRelations.map { it.followeeId }.toSet()
                val accs = when (type) {
                    FollowListType.FOLLOWING ->
                        followRelationRepository.getFollowingWithAccounts(AccountIds.USER_SELF_ID)
                    FollowListType.FOLLOWERS ->
                        followRelationRepository.getFollowersWithAccounts(AccountIds.USER_SELF_ID)
                    FollowListType.RECOMMENDED ->
                        loadRecommendedAccounts()
                }
                ids to accs
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // catch (Exception) 而非 catch (Throwable)，让 Error（OOM 等）自然传播
                Timber.w(e, "加载关注列表失败")
                emptySet<String>() to emptyList<AccountEntity>()
            }
            // 二次检查：DB 查询 await 期间若有 toggleFollow 添加 inflight，跳过覆盖保持乐观更新。
            // 仅当当前是 Loading 且之前是 Success 时恢复 prevUiState（避免卡 Loading）；
            // 若 toggleFollow 已乐观更新到 Success 则保持不动；若 prevUiState 也是 Loading（重入）则保持。
            if (inflightToggles.isNotEmpty()) {
                val currentState = _uiState.value
                if (currentState is FollowListUiState.Loading && prevUiState !is FollowListUiState.Loading) {
                    Timber.d("load 跳过覆盖：await 期间有 inflight toggle=%s，恢复 prevUiState", inflightToggles)
                    _uiState.value = prevUiState
                } else {
                    Timber.d("load 跳过覆盖：await 期间有 inflight toggle=%s，保持当前 _uiState=%s", inflightToggles, currentState)
                }
                return@launch
            }
            _followingIds.value = dbFollowingIds
            _uiState.value = FollowListUiState.Success(accounts)
        }
    }

    /**
     * 加载推荐关注账号列表（#146 A/E 场景 6 followRecommend）。
     *
     * - driven 组：按用户兴趣向量对虚拟账号 bio/profession 关键词匹配打分，取 Top N；
     * - control 组：随机打乱取 Top N；
     * - 排除已关注账号与自身；
     * - 落 scenarioId=6 事件供 computeFeedbackEffect 做 A/B 回测关注转化率 delta。
     */
    private suspend fun loadRecommendedAccounts(): List<AccountEntity> {
        val sessionId = sessionManager.currentSessionId() ?: "follow_recommend"
        val drivenScenario6 = feedbackController.shouldApply(ScenarioIds.FOLLOW_RECOMMEND, sessionId)
        // 缓存本次 RECOMMENDED 列表的 driven 分组结果，供 toggleFollow 在 FOLLOW 埋点上带 scenarioId=6。
        scenario6Driven = drivenScenario6
        val interestVector = if (drivenScenario6) {
            // catch (Exception) 而非 runCatching，让 Error（OOM）自然传播
            try {
                readAccess.interestVector()
            } catch (e: Exception) {
                Timber.w(e, "loadRecommendedAccounts: interestVector 失败")
                emptyMap()
            }
        } else {
            emptyMap()
        }

        // #318：单条 SQL 完成 isVirtual / 非自身 / 未关注三层过滤，替代原 while(true)
        // 翻页 getAccounts(page) + 内存 filter 的全量加载模式（N 个虚拟账号 → ceil(N/20)
        // 次 DB 查询）。SQL 层过滤后候选集 ≤ 虚拟账号总数，内存打分/随机成本相同。
        val candidates = try {
            accountRepository.getCandidateVirtualAccounts(AccountIds.USER_SELF_ID)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // catch (Exception) 而非 catch (Throwable)，让 Error 自然传播
            Timber.w(e, "加载候选虚拟账号失败")
            emptyList()
        }
        if (candidates.isEmpty()) return emptyList()

        val recommended = if (drivenScenario6 && interestVector.isNotEmpty()) {
            // driven 组：按兴趣关键词匹配打分，取 Top RECOMMEND_LIMIT
            candidates.map { account ->
                val keywords = extractKeywords(account.bio + " " + account.profession)
                val score = keywords.sumOf { (interestVector[it] ?: 0.0) }
                account to score
            }.sortedByDescending { it.second }
                .take(RECOMMEND_LIMIT)
                .map { it.first }
        } else {
            // control 组：随机打乱取 Top RECOMMEND_LIMIT
            candidates.shuffled().take(RECOMMEND_LIMIT)
        }

        // #146 A/E 场景 6：反哺层打标——为本次推荐列表发 scenario 事件，供 computeFeedbackEffect 做 A/B 回测。
        // 必须带 isScenarioMarker=true，使 OPEN_FOLLOWLIST 计入曝光分母。
        try {
            userActionTracker.trackNow(
                UserActionEvent(
                    id = UUID.randomUUID().toString(),
                    type = UserActionType.OPEN_FOLLOWLIST,
                    screen = "follow_recommend",
                    targetId = "recommend_list",
                    targetKind = "account_list",
                    extra = mapOf(
                        "scenarioId" to kotlinx.serialization.json.JsonPrimitive(ScenarioIds.FOLLOW_RECOMMEND),
                        "drivenByProfile" to kotlinx.serialization.json.JsonPrimitive(drivenScenario6),
                        "group" to kotlinx.serialization.json.JsonPrimitive(if (drivenScenario6) "driven" else "control"),
                        "isScenarioMarker" to kotlinx.serialization.json.JsonPrimitive(true),
                        "recommendCount" to kotlinx.serialization.json.JsonPrimitive(recommended.size),
                    ),
                    occurredAt = System.currentTimeMillis(),
                    session = sessionId,
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "#146 场景 6 打标失败")
        }

        return recommended
    }

    /**
     * 简单关键词提取（#146 场景 6）：按空白与标点分词，保留长度 >=2 的 token 并小写化。
     * 与 InteractionWorker.extractKeywords 行为一致，用于兴趣向量匹配。
     */
    private fun extractKeywords(text: String): Set<String> {
        return text.split(Regex("[\\s,，。、；;:：!！?？]+"))
            .filter { it.isNotBlank() && it.length >= 2 }
            .map { it.lowercase() }
            .toSet()
    }

    /**
     * 切换对某账号的关注状态（关注/取关）。
     *
     * - **真正乐观更新**：先翻转本地 [_followingIds] 与列表，再后台写库；DB 失败时回滚本地状态。
     * - **埋点去重**：DB 写成功后才发 FOLLOW 埏点，并用 in-flight 标记阻止同一账号的并发 toggle。
     *
     * #211：不切 Loading 态避免闪烁。
     *
     * 在 RECOMMENDED 列表里的 FOLLOW/UNFOLLOW 埏点带 scenarioId=6 / drivenByProfile / group，
     * 使 computeScenarioStats 能将"真实用户关注"计入互动分子。
     */
    fun toggleFollow(type: FollowListType, accountId: String) {
        // in-flight 去重：阻止同一账号在 DB 写盘完成前重复 toggle
        if (accountId in inflightToggles) {
            Timber.d("toggleFollow 跳过：accountId=%s 已有进行中操作", accountId)
            return
        }
        val isFollowing = accountId in _followingIds.value
        inflightToggles.add(accountId)

        // 真正乐观更新：先翻转本地状态
        _followingIds.value = if (isFollowing) {
            _followingIds.value - accountId
        } else {
            _followingIds.value + accountId
        }
        // 乐观更新本地 accounts 列表，不切 Loading 态以避免闪烁。
        // 乐观更新前先保留被移除的账号对象，DB 失败时用于回滚——否则回滚时
        // rollbackState.accounts 已是被乐观更新后的列表，find 找不到原账号对象，
        // 无法把它加回去。
        val currentState = _uiState.value
        val removedAccount: AccountEntity? = if (currentState is FollowListUiState.Success) {
            when (type) {
                FollowListType.FOLLOWING -> if (isFollowing) {
                    currentState.accounts.find { it.id == accountId }
                } else null
                FollowListType.RECOMMENDED -> if (!isFollowing) {
                    currentState.accounts.find { it.id == accountId }
                } else null
                FollowListType.FOLLOWERS -> null
            }
        } else null
        if (currentState is FollowListUiState.Success) {
            val updatedAccounts = when (type) {
                FollowListType.FOLLOWING -> {
                    if (isFollowing) currentState.accounts.filter { it.id != accountId }
                    else currentState.accounts
                }
                FollowListType.RECOMMENDED -> {
                    if (!isFollowing) currentState.accounts.filter { it.id != accountId }
                    else currentState.accounts
                }
                FollowListType.FOLLOWERS -> currentState.accounts
            }
            if (updatedAccounts !== currentState.accounts) {
                _uiState.value = FollowListUiState.Success(updatedAccounts)
            }
        }

        viewModelScope.launch {
            var dbWriteSuccess = false
            try {
                if (isFollowing) {
                    followRelationRepository.unfollow(AccountIds.USER_SELF_ID, accountId)
                    dbWriteSuccess = true
                } else {
                    // 检查 insert 返回值：OnConflictStrategy.IGNORE 时返回 -1（未实际插入），
                    // 此时不应发 FOLLOW 埏点（避免 A/B 场景 6 delta 高估）。
                    val insertedRowId = followRelationRepository.insert(
                        FollowRelationEntity(
                            followerId = AccountIds.USER_SELF_ID,
                            followeeId = accountId,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                    dbWriteSuccess = insertedRowId != -1L
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // catch (Exception) 而非 catch (Throwable)，让 Error 自然传播
                Timber.w(e, "切换关注状态失败，回滚本地状态 accountId=%s", accountId)
                // DB 失败回滚本地乐观更新
                _followingIds.value = if (isFollowing) {
                    _followingIds.value + accountId
                } else {
                    _followingIds.value - accountId
                }
                val rollbackState = _uiState.value
                if (rollbackState is FollowListUiState.Success && removedAccount != null) {
                    val restoredAccounts: List<AccountEntity> = when (type) {
                        FollowListType.FOLLOWING -> {
                            // 取关失败回滚：把账号加回 FOLLOWING 列表
                            if (isFollowing && rollbackState.accounts.none { it.id == accountId }) {
                                rollbackState.accounts + removedAccount
                            } else {
                                rollbackState.accounts
                            }
                        }
                        FollowListType.RECOMMENDED -> {
                            // 关注失败回滚：把账号加回 RECOMMENDED 列表
                            if (!isFollowing && rollbackState.accounts.none { it.id == accountId }) {
                                rollbackState.accounts + removedAccount
                            } else {
                                rollbackState.accounts
                            }
                        }
                        FollowListType.FOLLOWERS -> rollbackState.accounts
                    }
                    if (restoredAccounts !== rollbackState.accounts) {
                        _uiState.value = FollowListUiState.Success(restoredAccounts)
                    }
                }
            } finally {
                inflightToggles.remove(accountId)
            }
            // emit 移出 try 块：DB 写成功后才发埋点，埋点失败不触发回滚（DB 已写成功）。
            // 只有 dbWriteSuccess=true 才发埋点，IGNORE 静默跳过。
            if (dbWriteSuccess) {
                val extraBuilder = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                    "listType" to kotlinx.serialization.json.JsonPrimitive(type.name),
                )
                if (type == FollowListType.RECOMMENDED) {
                    scenario6Driven?.let { driven ->
                        extraBuilder["scenarioId"] = kotlinx.serialization.json.JsonPrimitive(ScenarioIds.FOLLOW_RECOMMEND)
                        extraBuilder["drivenByProfile"] = kotlinx.serialization.json.JsonPrimitive(driven)
                        extraBuilder["group"] = kotlinx.serialization.json.JsonPrimitive(if (driven) "driven" else "control")
                    }
                }
                try {
                    actionBuilder.emit(
                        type = if (isFollowing) UserActionType.UNFOLLOW else UserActionType.FOLLOW,
                        screen = "followlist",
                        targetId = accountId,
                        targetKind = "account",
                        extra = extraBuilder,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // catch (Exception) 而非 catch (Throwable)，让 Error 自然传播。
                    // 埋点失败不影响业务（DB 已写成功），仅记录日志
                    Timber.w(e, "toggleFollow 埋点失败 accountId=%s", accountId)
                }
            }
        }
    }

    private companion object {
        /** #146 场景 6：推荐关注列表上限 */
        const val RECOMMEND_LIMIT = 20
    }
}

/** 关注列表类型。 */
enum class FollowListType(val title: String) {
    FOLLOWING("关注"),
    FOLLOWERS("粉丝"),
    /** #146 A/E 场景 6：推荐关注（画像驱动） */
    RECOMMENDED("推荐关注"),
}

/** 关注列表 UI 状态。 */
sealed interface FollowListUiState {
    data object Loading : FollowListUiState
    data class Success(val accounts: List<AccountEntity>) : FollowListUiState
}
