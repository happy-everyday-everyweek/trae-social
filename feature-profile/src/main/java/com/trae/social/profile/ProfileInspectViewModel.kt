package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.trae.social.core.data.model.OverrideRecord
import com.trae.social.core.data.model.RollbackRecord
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.data.model.UserProfileVersion
import com.trae.social.core.data.model.VersionSummary
import com.trae.social.core.profiling.feedback.ProfileVersionStore
import com.trae.social.core.profiling.feedback.UserProfileReadAccess
import com.trae.social.core.scheduler.work.UserProfileWorker
import com.trae.social.core.scheduler.work.WorkerTags
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * DevOptionsScreen "我的画像" 区块 ViewModel（#146 验收项）。
 *
 * 暴露：
 * - [activeVersion]：当前激活 LLM 版本（narrative + feedbackWeights + overrideAcknowledgment）
 * - [snapshot]：最新基础分析快照（evidence + confidence）
 * - [activeOverrides]：生效覆盖列表
 * - [recentVersions]：历史版本时间线（含 isActive 标记，可点击回滚）
 * - [rollbackHistory]：回滚历史审计
 * - [triggerResult]：手动触发 UserProfileWorker 的反馈
 *
 * 操作：
 * - [triggerUserProfileUpdate]：手动触发一次 LLM 画像生成
 * - [rollbackTo]：直接回滚到指定版本（无需经智能体，DevOptions 提供快速入口）
 * - [refresh]：手动刷新所有展示数据
 */
@HiltViewModel
class ProfileInspectViewModel @Inject constructor(
    @ApplicationContext private val appContext: android.content.Context,
    private val readAccess: UserProfileReadAccess,
    private val versionStore: ProfileVersionStore,
) : ViewModel() {

    private val _activeVersion = MutableStateFlow<UserProfileVersion?>(null)
    val activeVersion: StateFlow<UserProfileVersion?> = _activeVersion.asStateFlow()

    private val _snapshot = MutableStateFlow<UserProfileSnapshot?>(null)
    val snapshot: StateFlow<UserProfileSnapshot?> = _snapshot.asStateFlow()

    private val _activeOverrides = MutableStateFlow<List<OverrideRecord>>(emptyList())
    val activeOverrides: StateFlow<List<OverrideRecord>> = _activeOverrides.asStateFlow()

    private val _recentVersions = MutableStateFlow<List<VersionSummary>>(emptyList())
    val recentVersions: StateFlow<List<VersionSummary>> = _recentVersions.asStateFlow()

    private val _rollbackHistory = MutableStateFlow<List<RollbackRecord>>(emptyList())
    val rollbackHistory: StateFlow<List<RollbackRecord>> = _rollbackHistory.asStateFlow()

    private val _triggerResult = MutableStateFlow<String?>(null)
    val triggerResult: StateFlow<String?> = _triggerResult.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                _activeVersion.value = readAccess.activeVersion()
                _snapshot.value = readAccess.latestSnapshot()
                _activeOverrides.value = readAccess.activeOverrides()
                _recentVersions.value = versionStore.recentSummaries(RECENT_LIMIT)
                _rollbackHistory.value = versionStore.rollbackHistory()
            }.onFailure { Timber.w(it, "刷新画像展示数据失败") }
        }
    }

    /**
     * 手动触发一次 LLM 画像更新（OneTimeWorkRequest，REPLACE 策略防重复入队）。
     */
    fun triggerUserProfileUpdate() {
        viewModelScope.launch {
            runCatching {
                val request = OneTimeWorkRequestBuilder<UserProfileWorker>()
                    .addTag(WorkerTags.USER_PROFILE)
                    .build()
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    UNIQUE_WORK_USER_PROFILE,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
                _triggerResult.value = "已触发：用户画像更新"
            }.onFailure {
                Timber.e(it, "手动触发用户画像更新失败")
                _triggerResult.value = "触发失败：${it.message}"
            }
        }
    }

    /**
     * DevOptionsScreen 版本时间线"回滚到此版本"按钮：直接应用回滚（不经智能体）。
     *
     * 回滚后会刷新展示数据。
     */
    fun rollbackTo(versionId: Long) {
        viewModelScope.launch {
            runCatching {
                versionStore.applyRollback(versionId, reason = "DevOptions 手动回滚")
                _triggerResult.value = "已回滚到版本 #$versionId"
                refresh()
            }.onFailure {
                Timber.w(it, "DevOptions 回滚失败")
                _triggerResult.value = "回滚失败：${it.message}"
            }
        }
    }

    fun clearTriggerResult() {
        _triggerResult.value = null
    }

    private companion object {
        const val RECENT_LIMIT = 20
        const val UNIQUE_WORK_USER_PROFILE = "manual_user_profile"
    }
}
