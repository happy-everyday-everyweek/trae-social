package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.dao.SchedulerLogDao
import com.trae.social.core.data.entity.SchedulerLogEntity
import com.trae.social.core.data.repository.ConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 开发者选项 ViewModel（IMPL-2 + RISK-15：可观测性）。
 *
 * 暴露调度日志流与当前 AI 活跃度档位，供开发者选项页查看。
 */
@HiltViewModel
class DevOptionsViewModel @Inject constructor(
    private val schedulerLogDao: SchedulerLogDao,
    private val configRepository: ConfigRepository,
) : ViewModel() {

    /**
     * 最近的调度日志（RISK-15）。
     */
    val logsFlow: StateFlow<List<SchedulerLogEntity>> = schedulerLogDao.observeRecent(LOG_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activityLevel = MutableStateFlow(AiActivityLevel.MEDIUM)
    val activityLevel: StateFlow<AiActivityLevel> = _activityLevel.asStateFlow()

    init {
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

    companion object {
        private const val LOG_LIMIT = 200
    }
}
