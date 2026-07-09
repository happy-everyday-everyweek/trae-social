package com.trae.social.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.dao.ActionCount
import com.trae.social.core.data.dao.CallStatistics
import com.trae.social.core.data.dao.SchedulerLogDao
import com.trae.social.core.data.entity.SchedulerLogEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.scheduler.work.PendingInteractionWorker
import com.trae.social.core.scheduler.work.PersonaUpdateWorker
import com.trae.social.core.scheduler.work.WorkerPolicies
import com.trae.social.core.scheduler.work.WorkerTags
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * 暴露调度日志流、当前 AI 活跃度档位、LLM 调用统计，以及手动触发调度的能力。
 */
@HiltViewModel
class DevOptionsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val schedulerLogDao: SchedulerLogDao,
    private val configRepository: ConfigRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    /**
     * 最近的调度日志（RISK-15）。
     */
    val logsFlow: StateFlow<List<SchedulerLogEntity>> = schedulerLogDao.observeRecent(LOG_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activityLevel = MutableStateFlow(AiActivityLevel.MEDIUM)
    val activityLevel: StateFlow<AiActivityLevel> = _activityLevel.asStateFlow()

    /** RISK-15：LLM 调用统计 */
    private val _llmStats = MutableStateFlow<CallStatistics?>(null)
    val llmStats: StateFlow<CallStatistics?> = _llmStats.asStateFlow()

    /** RISK-15：按 action 分组的调用计数 */
    private val _actionCounts = MutableStateFlow<List<ActionCount>>(emptyList())
    val actionCounts: StateFlow<List<ActionCount>> = _actionCounts.asStateFlow()

    /** 手动触发结果反馈 */
    private val _triggerResult = MutableStateFlow<String?>(null)
    val triggerResult: StateFlow<String?> = _triggerResult.asStateFlow()

    init {
        viewModelScope.launch {
            _activityLevel.value = runCatching { configRepository.getAiActivityLevel() }
                .getOrElse { AiActivityLevel.MEDIUM }
        }
        refreshStats()
    }

    fun setActivityLevel(level: AiActivityLevel) {
        viewModelScope.launch {
            runCatching { configRepository.setAiActivityLevel(level) }
                .onSuccess { _activityLevel.value = level }
                .onFailure { Timber.w(it, "切换活跃度档位失败") }
        }
    }

    /**
     * RISK-15：刷新 LLM 调用统计。
     */
    fun refreshStats() {
        viewModelScope.launch {
            runCatching {
                _llmStats.value = schedulerLogDao.getCallStatistics()
                _actionCounts.value = schedulerLogDao.countByAction()
            }.onFailure { Timber.w(it, "刷新 LLM 调用统计失败") }
        }
    }

    /**
     * RISK-15：手动触发一次推文生成调度。
     *
     * 从虚拟账号中随机选取一个，入队即时 TweetGenerationWorker。
     */
    fun triggerTweetGeneration() {
        viewModelScope.launch {
            runCatching {
                val accounts = accountRepository.getAccounts(1).filter { it.isVirtual }
                if (accounts.isEmpty()) {
                    _triggerResult.value = "无可用虚拟账号"
                    return@runCatching
                }
                val account = accounts.random()
                val windowStart = System.currentTimeMillis()
                val dedupKey = "manual_${account.id}_$windowStart"
                val request = WorkerPolicies.tweetGenerationRequest(
                    accountId = account.id,
                    deduplicationKey = dedupKey,
                    windowStart = windowStart,
                    sequenceNo = 0,
                )
                WorkManager.getInstance(appContext).enqueue(request)
                _triggerResult.value = "已触发：账号 ${account.id} 的推文生成"
            }.onFailure {
                Timber.e(it, "手动触发推文生成失败")
                _triggerResult.value = "触发失败：${it.message}"
            }
        }
    }

    /**
     * RISK-15：手动触发一次待执行互动处理。
     */
    fun triggerPendingInteractions() {
        viewModelScope.launch {
            runCatching {
                val request = OneTimeWorkRequestBuilder<PendingInteractionWorker>()
                    .addTag(WorkerTags.PENDING_INTERACTION)
                    .build()
                WorkManager.getInstance(appContext).enqueue(request)
                _triggerResult.value = "已触发：待执行互动处理"
            }.onFailure {
                Timber.e(it, "手动触发互动处理失败")
                _triggerResult.value = "触发失败：${it.message}"
            }
        }
    }

    /**
     * RISK-15：手动触发一次人设更新。
     */
    fun triggerPersonaUpdate() {
        viewModelScope.launch {
            runCatching {
                val request = OneTimeWorkRequestBuilder<PersonaUpdateWorker>()
                    .addTag(WorkerTags.PERSONA_UPDATE)
                    .build()
                WorkManager.getInstance(appContext).enqueue(request)
                _triggerResult.value = "已触发：人设动态字段更新"
            }.onFailure {
                Timber.e(it, "手动触发人设更新失败")
                _triggerResult.value = "触发失败：${it.message}"
            }
        }
    }

    fun clearTriggerResult() {
        _triggerResult.value = null
    }

    companion object {
        private const val LOG_LIMIT = 200
    }
}
