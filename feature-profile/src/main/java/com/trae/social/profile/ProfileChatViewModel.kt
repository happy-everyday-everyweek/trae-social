package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.dao.UserProfileFeedbackDao
import com.trae.social.core.data.entity.UserProfileFeedbackEntity
import com.trae.social.core.data.model.AgentReply
import com.trae.social.core.data.model.OverrideRecord
import com.trae.social.core.data.model.RollbackPreview
import com.trae.social.core.data.model.RollbackResult
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.data.model.UserProfileVersion
import com.trae.social.core.data.model.VersionSummary
import com.trae.social.core.profiling.feedback.FeedbackAgent
import com.trae.social.core.profiling.feedback.ProfileAdjuster
import com.trae.social.core.profiling.feedback.ProfileVersionStore
import com.trae.social.core.profiling.feedback.UserProfileReadAccess
import com.trae.social.core.profiling.mapping.ProfileMappers
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 画像对话页 ViewModel（#146 第五层）。
 *
 * 暴露：
 * - [messages]：对话历史（最近 N 条），跨会话持久化（来自 [UserProfileFeedbackDao]）
 * - [activeVersion] / [snapshot] / [activeOverrides] / [recentVersions]：当前画像状态，顶部卡片展示
 * - [sending]：是否正在等待智能体回复（控制发送按钮禁用与 loading 态）
 * - [pendingPreviews]：待确认的回滚预览列表（智能体回复中携带）
 *
 * 操作：
 * - [send]：发送用户消息 → [FeedbackAgent.handle] → 渲染回复 + 应用覆盖 / 生成回滚预览
 * - [confirmRollback]：用户点击预览卡片"确认回滚" → [FeedbackAgent.confirmRollback]
 * - [dismissPreview]：用户忽略某条回滚预览
 * - [resetAllOverrides]：一键撤销所有用户覆盖（"重置所有调整"按钮）
 */
@HiltViewModel
class ProfileChatViewModel @Inject constructor(
    private val feedbackAgent: FeedbackAgent,
    private val adjuster: ProfileAdjuster,
    private val versionStore: ProfileVersionStore,
    private val readAccess: UserProfileReadAccess,
    private val feedbackDao: UserProfileFeedbackDao,
) : ViewModel() {

    /** 对话历史（按 createdAt 升序，最早在前）。 */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** 当前激活的 LLM 画像版本（顶部摘要卡片展示）。 */
    private val _activeVersion = MutableStateFlow<UserProfileVersion?>(null)
    val activeVersion: StateFlow<UserProfileVersion?> = _activeVersion.asStateFlow()

    /** 最新基础分析快照（顶部摘要卡片展示 evidence + confidence）。 */
    private val _snapshot = MutableStateFlow<UserProfileSnapshot?>(null)
    val snapshot: StateFlow<UserProfileSnapshot?> = _snapshot.asStateFlow()

    /** 当前生效覆盖（顶部摘要卡片展示数量）。 */
    private val _activeOverrides = MutableStateFlow<List<OverrideRecord>>(emptyList())
    val activeOverrides: StateFlow<List<OverrideRecord>> = _activeOverrides.asStateFlow()

    /** 最近版本列表（"查看版本历史"按钮展开）。 */
    private val _recentVersions = MutableStateFlow<List<VersionSummary>>(emptyList())
    val recentVersions: StateFlow<List<VersionSummary>> = _recentVersions.asStateFlow()

    /** 是否正在等待智能体回复。 */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** 待确认的回滚预览（智能体回复中携带，用户点击"确认回滚"后调 [confirmRollback]）。 */
    private val _pendingPreviews = MutableStateFlow<List<RollbackPreview>>(emptyList())
    val pendingPreviews: StateFlow<List<RollbackPreview>> = _pendingPreviews.asStateFlow()

    /** 用户可读的失败/降级提示（一次性 Snackbar 消费）。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        refreshProfile()
        loadHistory()
    }

    /**
     * 发送用户消息 → 调用 [FeedbackAgent.handle] → 追加回复到 [messages]。
     *
     * 智能体内部已处理：限流 / LLM 不可用降级 / 解析失败澄清 / 应用覆盖 / 生成回滚预览。
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _sending.value) return
        _sending.value = true
        // 先乐观追加用户消息（不持久化，handle 内部会持久化；保持 UI 即时反馈）
        _messages.value = _messages.value + ChatMessage.user(trimmed, System.currentTimeMillis())
        viewModelScope.launch {
            // M3 修复：原 runCatching 会吞掉 CancellationException，导致 viewModelScope 取消时
            // （如退出屏幕）仍生成降级回复并更新 _messages，破坏协程取消语义。改为 try/catch
            // 并重抛 CancellationException，让取消信号正常传播。
            val reply: AgentReply = try {
                feedbackAgent.handle(trimmed)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Timber.w(t, "FeedbackAgent.handle 失败")
                AgentReply(
                    text = "智能体暂时不可用：${t.message ?: "未知错误"}",
                    appliedActions = emptyList(),
                    rollbackPreviews = emptyList(),
                    degraded = true,
                )
            }
            _messages.value = _messages.value + ChatMessage.assistant(reply)
            if (reply.rollbackPreviews.isNotEmpty()) {
                _pendingPreviews.value = _pendingPreviews.value + reply.rollbackPreviews
            }
            refreshProfile()
            _sending.value = false
        }
    }

    /** 用户在回滚预览卡片上点击"确认回滚"。 */
    fun confirmRollback(preview: RollbackPreview) {
        viewModelScope.launch {
            // M3 修复：同 send()，runCatching 改 try/catch 并重抛 CancellationException。
            val result: RollbackResult = try {
                feedbackAgent.confirmRollback(preview, reason = "用户在对话中确认回滚")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Timber.w(t, "confirmRollback 失败")
                _toast.value = "回滚失败：${t.message ?: "未知错误"}"
                return@launch
            }
            _pendingPreviews.value = _pendingPreviews.value.filterNot { it.targetVersionId == preview.targetVersionId }
            _messages.value = _messages.value + ChatMessage.assistant(
                AgentReply(
                    text = "已回滚到版本 #${result.toVersionId}（来自 #${result.fromVersionId}），" +
                        "反哺策略已切换到旧版本。用户覆盖仍然保留。",
                    appliedActions = emptyList(),
                    rollbackPreviews = emptyList(),
                )
            )
            refreshProfile()
            _toast.value = "已回滚到版本 #${result.toVersionId}"
        }
    }

    /** 用户忽略某条回滚预览（点击"取消"）。 */
    fun dismissPreview(preview: RollbackPreview) {
        _pendingPreviews.value = _pendingPreviews.value.filterNot { it.targetVersionId == preview.targetVersionId }
    }

    /**
     * "重置所有调整"按钮：一键撤销所有用户覆盖。
     *
     * 重置后刷新画像摘要，并追加一条系统消息提示用户。
     */
    fun resetAllOverrides() {
        viewModelScope.launch {
            // M3 修复：同 send()，runCatching 改 try/catch 并重抛 CancellationException。
            val count = try {
                adjuster.resetAll()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Timber.w(t, "resetAllOverrides 失败")
                _toast.value = "重置失败：${t.message ?: "未知错误"}"
                return@launch
            }
            _pendingPreviews.value = emptyList()
            _messages.value = _messages.value + ChatMessage.assistant(
                AgentReply(
                    text = "已撤销 $count 条用户覆盖，画像回到算法计算值。",
                    appliedActions = emptyList(),
                    rollbackPreviews = emptyList(),
                )
            )
            refreshProfile()
            _toast.value = "已撤销 $count 条覆盖"
        }
    }

    /** 顶部卡片"查看版本历史"按钮：手动加载最近 N 个版本摘要。 */
    fun refreshRecentVersions() {
        viewModelScope.launch {
            _recentVersions.value = runCatching { versionStore.recentSummaries(RECENT_VERSIONS_LIMIT) }
                .getOrDefault(emptyList())
        }
    }

    /** 消费一次性 toast。 */
    fun consumeToast() {
        _toast.value = null
    }

    private fun refreshProfile() {
        _activeVersion.value = readAccess.activeVersion()
        _snapshot.value = readAccess.latestSnapshot()
        _activeOverrides.value = readAccess.activeOverrides()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val history = runCatching { feedbackDao.recent(HISTORY_LIMIT) }
                .getOrDefault(emptyList())
                .sortedBy { it.createdAt }
                .map { it.toChatMessage() }
            // 第二轮 review Minor 8 修复:loadHistory 异步从 DB 读取并整体替换 _messages,
            // 若 loadHistory 完成于 send 乐观追加之后、feedbackAgent.handle 持久化之前,
            // 历史替换会覆盖乐观追加的消息。改为合并:用历史做基底,叠加当前 _messages 中
            // 尚未持久化的乐观追加消息(以 history 末尾 timestamp 为界,时间戳大于该值的
            // 视为乐观追加且尚未落盘的消息,避免被历史覆盖丢失)。
            val current = _messages.value
            if (current.isEmpty()) {
                _messages.value = history
            } else {
                val maxHistoryTs = history.lastOrNull()?.timestamp ?: 0L
                val pending = current.filter { it.timestamp > maxHistoryTs }
                _messages.value = history + pending
            }
        }
    }

    private fun UserProfileFeedbackEntity.toChatMessage(): ChatMessage = if (role == ROLE_USER) {
        ChatMessage.user(content, createdAt)
    } else {
        val applied = appliedActions?.let {
            runCatching {
                ProfileMappers.json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(OverrideRecord.serializer()),
                    it,
                )
            }.getOrDefault(emptyList())
        } ?: emptyList()
        val previews = rollbackPreviews?.let {
            runCatching {
                ProfileMappers.json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(RollbackPreview.serializer()),
                    it,
                )
            }.getOrDefault(emptyList())
        } ?: emptyList()
        ChatMessage.assistant(
            AgentReply(
                text = content,
                appliedActions = applied,
                rollbackPreviews = previews,
            )
        )
    }

    private companion object {
        const val HISTORY_LIMIT = 50
        const val RECENT_VERSIONS_LIMIT = 20
        const val ROLE_USER = "USER"
    }
}

/**
 * 对话消息 UI 模型。
 *
 * 用户消息右对齐，智能体消息左对齐；assistant 消息可附带"已应用调整"标签与回滚预览卡片。
 */
data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val timestamp: Long,
    val appliedActions: List<OverrideRecord> = emptyList(),
    val rollbackPreviews: List<RollbackPreview> = emptyList(),
    val degraded: Boolean = false,
) {
    enum class Role { USER, ASSISTANT }

    companion object {
        // 第二轮 review Nit 修复:seq 当前仅在 viewModelScope(Main.immediate) 单线程调用所以安全,
        // 但跨 ViewModel 实例共享且无并发保护,改用 AtomicLong 防御并发递增场景。
        private val seq = java.util.concurrent.atomic.AtomicLong(0L)

        fun user(text: String, timestamp: Long): ChatMessage = ChatMessage(
            id = seq.getAndIncrement(),
            role = Role.USER,
            text = text,
            timestamp = timestamp,
        )

        fun assistant(reply: AgentReply): ChatMessage = ChatMessage(
            id = seq.getAndIncrement(),
            role = Role.ASSISTANT,
            text = reply.text,
            timestamp = System.currentTimeMillis(),
            appliedActions = reply.appliedActions,
            rollbackPreviews = reply.rollbackPreviews,
            degraded = reply.degraded,
        )
    }
}
