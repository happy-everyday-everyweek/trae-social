package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.dao.FollowRelationDao
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.FollowRelationEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.profiling.capture.SessionManager
import com.trae.social.core.profiling.capture.UserActionEventBuilder
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.profile.di.ProfileImageLoader
import coil.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 关注/粉丝列表 ViewModel（IMPL-2）。
 *
 * 根据 [FollowListType] 拉取自身账号的关注或粉丝关系，再 join 出账号资料。
 */
@HiltViewModel
class FollowListViewModel @Inject constructor(
    private val followRelationDao: FollowRelationDao,
    private val accountRepository: AccountRepository,
    private val userActionTracker: UserActionTracker,
    private val sessionManager: SessionManager,
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
                }
            }.getOrElse {
                Timber.w(it, "加载关注列表失败")
                emptyList()
            }
            _uiState.value = FollowListUiState.Success(accounts)
        }
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
}

/** 关注列表类型。 */
enum class FollowListType(val title: String) {
    FOLLOWING("关注"),
    FOLLOWERS("粉丝"),
}

/** 关注列表 UI 状态。 */
sealed interface FollowListUiState {
    data object Loading : FollowListUiState
    data class Success(val accounts: List<AccountEntity>) : FollowListUiState
}
