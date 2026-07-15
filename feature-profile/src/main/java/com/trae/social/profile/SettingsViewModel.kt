package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.repository.ConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 设置页 ViewModel（#143）。
 *
 * 从 ProfileViewModel 拆分出来，仅负责设置页所需的状态：
 * - 读取/切换 AI 活跃度档位
 *
 * 避免复用 ProfileViewModel 导致冗余初始化（loadProfile / loadInitialLikedTweetIds）
 * 与跨实例状态不同步问题（SettingsScreen 修改 activityLevel 后 ProfileScreen 实例不感知）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _activityLevel = MutableStateFlow(AiActivityLevel.MEDIUM)
    val activityLevel: StateFlow<AiActivityLevel> = _activityLevel.asStateFlow()

    init {
        loadActivityLevel()
    }

    private fun loadActivityLevel() {
        viewModelScope.launch {
            _activityLevel.value = runCatching { configRepository.getAiActivityLevel() }
                .getOrElse { AiActivityLevel.MEDIUM }
        }
    }

    fun setActivityLevel(level: AiActivityLevel) {
        viewModelScope.launch {
            runCatching { configRepository.setAiActivityLevel(level) }
                .onSuccess { _activityLevel.value = level }
                .onFailure { Timber.w(it, "切换活跃度档位失败") }
        }
    }
}
