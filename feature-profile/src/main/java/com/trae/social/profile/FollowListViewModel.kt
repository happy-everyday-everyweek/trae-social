package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.dao.FollowRelationDao
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.repository.AccountRepository
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
    @ProfileImageLoader val imageLoader: ImageLoader,
) : ViewModel() {

    private val _uiState = MutableStateFlow<FollowListUiState>(FollowListUiState.Loading)
    val uiState: StateFlow<FollowListUiState> = _uiState.asStateFlow()

    fun load(type: FollowListType) {
        viewModelScope.launch {
            _uiState.value = FollowListUiState.Loading
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
