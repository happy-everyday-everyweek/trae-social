package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.AccountIds
import com.trae.social.core.data.dao.FollowRelationDao
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.FollowRelationEntity
import com.trae.social.core.data.repository.AccountRepository
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
    private val followRelationDao: FollowRelationDao,
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
     * 主 review 第 1 轮 M2 修复：in-flight toggle 去重集合。
     *
     * 记录 DB 写盘尚未完成的 accountId，阻止同一账号在 DB 写完成前重复 toggle。
     * toggleFollow 在主线程同步 add，DB 写完成后在 finally 中 remove；
     * viewModelScope 默认 Dispatchers.Main.immediate，无需额外同步。
     */
    private val inflightToggles: MutableSet<String> = mutableSetOf()

    /**
     * #146 A/E 场景 6：最近一次 RECOMMENDED 列表的 driven 分组结果。
     *
     * 第七轮 review B1 修复：toggleFollow 落 FOLLOW 埋点时需带上 scenarioId=6 / drivenByProfile / group，
     * 才能让 computeScenarioStats 把"真实用户关注"计入互动分子，否则 OPEN_FOLLOWLIST 曝光打标
     * 与真实 FOLLOW 互动两不沾 → delta 恒为 0。
     */
    private var scenario6Driven: Boolean? = null

    fun load(type: FollowListType) {
        viewModelScope.launch {
            // 主 review 第 3 轮修复（竞态保护完整化）：第 2 轮只跳过 _followingIds 覆盖，
            // 但 _uiState 仍无条件切到 Loading 再用 DB 旧状态覆盖 Success——会丢失
            // toggleFollow 的乐观更新（用户点"取消关注 A"后下拉刷新会看到 A 又出现在
            // FOLLOWING 列表，但按钮态是"关注"，UI 不一致）。
            // 改为：hasInflight 时直接 return，让乐观更新保持；inflight 完成后 UI 可主动
            // 触发下一次 load 拿到 DB 最新状态。
            //
            // 主 review 第 4 轮修复（二次竞态保护）：第 3 轮只在 load 开始时检查 inflight，
            // 但 DB 查询是 suspend，await 期间用户可能点击 toggleFollow 添加 inflight 并
            // 同步更新 _followingIds（注：_uiState 此时是 Loading，toggleFollow 的乐观更新
            // 逻辑只在 currentState is Success 时更新 _uiState，所以 _uiState 不被更新）。
            // 查询返回后若直接覆盖会丢失 _followingIds 的乐观更新，且 _followingIds 与
            // _uiState 可能分别被覆盖导致互相不一致。
            // 改为：把 _followingIds 和 accounts 的 DB 查询放在同一个 try 块，查询完成后
            // 统一检查 inflightToggles——非空则跳过所有覆盖，保持乐观更新；为空则一次性
            // 覆盖两者，保证一致性。
            //
            // 主 review 第 5 轮修复（M1 回归修复）：第 4 轮的"跳过覆盖"会导致 _uiState
            // 卡在 Loading 态无恢复路径——load 开始时已切 Loading，await 期间 toggleFollow
            // 不更新 _uiState（因为 currentState 不是 Success），跳过覆盖后 _uiState 仍是
            // Loading，UI 永久显示"加载中…"。改为：跳过覆盖时恢复 _uiState 到 load 开始前
            // 的状态（prevUiState），让 UI 至少显示原列表（虽然数据略旧，但比卡死好）。
            //
            // 主 review 第 6 轮修复（M1 回归再次修复）：第 5 轮的"无条件恢复 prevUiState"
            // 仍有缺陷——await 期间 toggleFollow 可能把 _uiState 乐观更新到 Success（如果
            // toggleFollow 进入时 currentState 已经是 Success），此时恢复 prevUiState 会
            // 覆盖 toggleFollow 的乐观更新；更严重的是当 prevUiState is Loading（load 重入
            // 或单次 load 期间 toggleFollow 把 Loading 切到 Success 后又被恢复），恢复
            // prevUiState = Loading 会把 Success 覆盖回 Loading，UI 永久卡加载中。
            // 改为：二次检查跳过时，只在"当前 _uiState 是 Loading 且 prevUiState 不是 Loading"
            // 时才恢复 prevUiState——即只有 load 自己切的 Loading 且 prevUiState 是 Success
            // 时才恢复；如果当前已经是 Success（toggleFollow 已乐观更新），保持不动；
            // 如果 prevUiState 也是 Loading（load 重入），保持 Loading（inflight 完成后用户
            // 重新触发 load 拿到 DB 最新状态）。
            if (inflightToggles.isNotEmpty()) {
                Timber.d("load 跳过：有 inflight toggle=%s，避免覆盖乐观更新", inflightToggles)
                return@launch
            }
            val prevUiState = _uiState.value
            _uiState.value = FollowListUiState.Loading
            // 主 review 第 5 轮修复（m1）：FOLLOWING 分支原来调用 getFollowing 两次（一次取 ids，
            // 一次取 accounts），浪费 IO 且延长 await 窗口，两次查询间若发生 toggleFollow DB
            // 写盘完成会导致 ids 与 accounts 基于不同快照。改为单次查询，ids 和 accounts 复用
            // 同一 relations 结果，保证一致性。
            val (dbFollowingIds, accounts) = try {
                val followingRelations = followRelationDao.getFollowing(AccountIds.USER_SELF_ID)
                val ids = followingRelations.map { it.followeeId }.toSet()
                val accs = when (type) {
                    FollowListType.FOLLOWING -> {
                        followingRelations.mapNotNull { accountRepository.getById(it.followeeId) }
                    }
                    FollowListType.FOLLOWERS -> {
                        val relations = followRelationDao.getFollowers(AccountIds.USER_SELF_ID)
                        relations.mapNotNull { accountRepository.getById(it.followerId) }
                    }
                    FollowListType.RECOMMENDED -> {
                        // #146 A/E 场景 6 followRecommend：driven 组按用户兴趣打分推荐，control 组随机推荐
                        loadRecommendedAccounts()
                    }
                }
                ids to accs
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 主 review 第 4 轮修复：catch (Throwable) → catch (Exception) 让 Error 自然传播，
                // 与同模块 ApiKeyViewModel 策略一致。
                Timber.w(e, "加载关注列表失败")
                emptySet<String>() to emptyList<AccountEntity>()
            }
            // 二次检查：DB 查询 await 期间若有 toggleFollow 添加 inflight，跳过覆盖保持乐观更新。
            // 第 5 轮：恢复 _uiState 到 prevUiState 避免卡 Loading。
            // 第 6 轮（M1 回归再次修复）：只在"当前 _uiState 是 Loading 且 prevUiState 不是 Loading"
            // 时才恢复——避免覆盖 toggleFollow 已写入的 Success，或 prevUiState 也是 Loading 时
            // 无意义的"恢复到 Loading"。
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
        val drivenScenario6 = feedbackController.shouldApply(6, sessionId)
        // 第七轮 review B1 修复：缓存本次 RECOMMENDED 列表的 driven 分组结果，
        // 供 toggleFollow 在 FOLLOW 埋点上带 scenarioId=6 / drivenByProfile / group。
        scenario6Driven = drivenScenario6
        val interestVector = if (drivenScenario6) {
            // 主 review 第 4 轮修复：runCatching → try/catch。interestVector() 是非 suspend
            // 同步函数，不会抛 CancellationException，但 runCatching 会吞 Error（OOM），
            // 与同模块 ApiKeyViewModel 策略不一致。改为 catch (Exception) 让 Error 自然传播。
            try {
                readAccess.interestVector()
            } catch (e: Exception) {
                Timber.w(e, "loadRecommendedAccounts: interestVector 失败")
                emptyMap()
            }
        } else {
            emptyMap()
        }
        val followingIds = _followingIds.value

        // 翻页加载全部虚拟账号，排除已关注与自身
        val candidates = mutableListOf<AccountEntity>()
        var page = 1
        while (true) {
            val batch = try {
                accountRepository.getAccounts(page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 主 review 第 4 轮修复：catch (Throwable) → catch (Exception) 让 Error 自然传播。
                Timber.w(e, "翻页加载账号失败 page=%d", page)
                emptyList()
            }
            if (batch.isEmpty()) break
            candidates.addAll(
                batch.filter { it.isVirtual && it.id !in followingIds && it.id != AccountIds.USER_SELF_ID }
            )
            page++
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
        // 第七轮 review B1 修复：必须带 isScenarioMarker=true，使 OPEN_FOLLOWLIST 计入曝光分母，
        // 否则事件既非 marker 又非 INTERACTION_TYPES 互动 → delta 恒为 0。
        // 主 review 第 4 轮修复：runCatching → try/catch。trackNow 是非 suspend 同步函数，
        // 不会抛 CancellationException，但 runCatching 会吞 Error，与同模块策略不一致。
        try {
            userActionTracker.trackNow(
                UserActionEvent(
                    id = UUID.randomUUID().toString(),
                    type = UserActionType.OPEN_FOLLOWLIST,
                    screen = "follow_recommend",
                    targetId = "recommend_list",
                    targetKind = "account_list",
                    extra = mapOf(
                        "scenarioId" to kotlinx.serialization.json.JsonPrimitive(6),
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
     * 主 review 第 1 轮 M1+M2 修复：
     * - **真正乐观更新**：先翻转本地 [_followingIds] 与列表，再后台写库；DB 失败时回滚本地状态。
     *   旧实现把 `_followingIds.value = ...` 放在 launch 内 DB 写成功之后，按钮文案要等
     *   50-200ms DB 写盘才翻转，不是真正的"乐观更新"，与 PR 标题/注释自相矛盾。
     * - **埋点去重**：旧实现 `actionBuilder.emit(...)` 在 launch 前同步执行，快速连点两次
     *   会发两次 FOLLOW 埋点，但 DB 因 `OnConflictStrategy.IGNORE` 只插一条关系，
     *   导致 A/B 场景 6 关注转化率 delta 被高估。改为 DB 写成功后再发埋点，
     *   并用 in-flight 标记阻止同一账号的并发 toggle。
     *
     * #211：不切 Loading 态避免闪烁。
     *
     * 第七轮 review B1 修复：在 RECOMMENDED 列表里的 FOLLOW/UNFOLLOW 埋点需带上
     * scenarioId=6 / drivenByProfile / group，使 computeScenarioStats 能将"真实用户关注"
     * 计入互动分子；UNFOLLOW 不计入互动分子，但仍带 scenarioId 以便调试可追溯。
     */
    fun toggleFollow(type: FollowListType, accountId: String) {
        // in-flight 去重：阻止同一账号在 DB 写盘完成前重复 toggle（M2 修复）
        if (accountId in inflightToggles) {
            Timber.d("toggleFollow 跳过：accountId=%s 已有进行中操作", accountId)
            return
        }
        val isFollowing = accountId in _followingIds.value
        inflightToggles.add(accountId)

        // 真正乐观更新：先翻转本地状态（M1 修复）
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
                    followRelationDao.delete(AccountIds.USER_SELF_ID, accountId)
                    dbWriteSuccess = true
                } else {
                    // 主 review 第 2 轮修复：检查 insert 返回值。
                    // FollowRelationDao.insert 用 OnConflictStrategy.IGNORE，DB 中已存在关系时
                    // 返回 -1（未实际插入）。此时不应发 FOLLOW 埋点（避免 A/B 场景 6 delta 高估）。
                    val insertedRowId = followRelationDao.insert(
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
                // 主 review 第 4 轮修复：catch (Throwable) → catch (Exception) 让 Error 自然传播。
                Timber.w(e, "切换关注状态失败，回滚本地状态 accountId=%s", accountId)
                // DB 失败回滚本地乐观更新（M1 修复）
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
            // 主 review 第 2 轮修复：emit 移出 try 块——DB 写成功后才发埋点，
            // 且埋点失败不应触发上面的回滚（DB 已写成功，回滚会导致 UI 与 DB 不一致）。
            // 当前 actionBuilder.emit 内部用 channel.trySend 不抛异常，移出 try 后语义更清晰：
            // 只有 DB 写成功（dbWriteSuccess=true）才发埋点，IGNORE（dbWriteSuccess=false）静默跳过。
            if (dbWriteSuccess) {
                val extraBuilder = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                    "listType" to kotlinx.serialization.json.JsonPrimitive(type.name),
                )
                if (type == FollowListType.RECOMMENDED) {
                    scenario6Driven?.let { driven ->
                        extraBuilder["scenarioId"] = kotlinx.serialization.json.JsonPrimitive(6)
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
                    // 主 review 第 4 轮修复：catch (Throwable) → catch (Exception) 让 Error 自然传播。
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
