package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.trae.social.profile.di.ProfileImageLoader
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
    @ProfileImageLoader val imageLoader: ImageLoader,
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

    fun load(type: FollowListType) {
        viewModelScope.launch {
            _uiState.value = FollowListUiState.Loading
            // 先刷新"我关注了谁"集合，供按钮状态使用
            _followingIds.value = runCatching {
                followRelationDao.getFollowing(ProfileViewModel.SELF_ID).map { it.followeeId }.toSet()
            }.getOrElse { emptySet() }
            val accounts = runCatching {
                when (type) {
                    FollowListType.FOLLOWING -> {
                        val relations = followRelationDao.getFollowing(ProfileViewModel.SELF_ID)
                        relations.mapNotNull { accountRepository.getById(it.followeeId) }
                    }
                    FollowListType.FOLLOWERS -> {
                        val relations = followRelationDao.getFollowers(ProfileViewModel.SELF_ID)
                        relations.mapNotNull { accountRepository.getById(it.followerId) }
                    }
                    FollowListType.RECOMMENDED -> {
                        // #146 A/E 场景 6 followRecommend：driven 组按用户兴趣打分推荐，control 组随机推荐
                        loadRecommendedAccounts()
                    }
                }
            }.getOrElse {
                Timber.w(it, "加载关注列表失败")
                emptyList()
            }
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
        val interestVector = if (drivenScenario6) {
            runCatching { readAccess.interestVector() }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val followingIds = _followingIds.value

        // 翻页加载全部虚拟账号，排除已关注与自身
        val candidates = mutableListOf<AccountEntity>()
        var page = 1
        while (true) {
            val batch = runCatching { accountRepository.getAccounts(page) }.getOrDefault(emptyList())
            if (batch.isEmpty()) break
            candidates.addAll(
                batch.filter { it.isVirtual && it.id !in followingIds && it.id != ProfileViewModel.SELF_ID }
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
        runCatching {
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
                        "recommendCount" to kotlinx.serialization.json.JsonPrimitive(recommended.size),
                    ),
                    occurredAt = System.currentTimeMillis(),
                    session = sessionId,
                )
            )
        }.onFailure { Timber.w(it, "#146 场景 6 打标失败") }

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
     * 切换对某账号的关注状态（关注/取关），写库后刷新本地集合与列表。
     */
    fun toggleFollow(type: FollowListType, accountId: String) {
        val isFollowing = accountId in _followingIds.value
        // #146 B：关注/取关埋点（在落库前记录意图，extra 带 listType）
        actionBuilder.emit(
            type = if (isFollowing) UserActionType.UNFOLLOW else UserActionType.FOLLOW,
            screen = "followlist",
            targetId = accountId,
            targetKind = "account",
            extra = mapOf("listType" to kotlinx.serialization.json.JsonPrimitive(type.name)),
        )
        viewModelScope.launch {
            runCatching {
                if (isFollowing) {
                    followRelationDao.delete(ProfileViewModel.SELF_ID, accountId)
                } else {
                    followRelationDao.insert(
                        FollowRelationEntity(
                            followerId = ProfileViewModel.SELF_ID,
                            followeeId = accountId,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
            }.onFailure { Timber.w(it, "切换关注状态失败") }
            load(type)
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
