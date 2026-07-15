package com.trae.social.core.profiling.feedback

import com.trae.social.core.data.dao.UserProfileFeedbackDao
import com.trae.social.core.data.entity.UserProfileFeedbackEntity
import com.trae.social.core.data.model.AgentReply
import com.trae.social.core.data.model.FeedbackAction
import com.trae.social.core.data.model.OverrideRecord
import com.trae.social.core.data.model.RollbackPreview
import com.trae.social.core.data.model.RollbackResult
import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.profiling.capture.ProfilingGate
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.core.profiling.mapping.ProfileMappers
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.LlmProviderRegistry
import com.trae.social.llm.prompt.FeedbackAgentPromptBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户反馈智能体（#146 第五层）。
 *
 * 轻量、单轮、可控：单轮 LLM 解析意图 → 结构化 Action → 即时应用 → 自然语言回复。
 * 避免多轮 agent 失控，保证可审计、可撤销。回滚意图需预览确认，不直接应用。
 *
 * 安全约束：
 * - 白名单 Action 过滤 + 值域校验（[FeedbackAction.sanitize]）。
 * - LLM 不可用时降级（返回降级菜单）。
 * - 意图模糊时澄清（[FeedbackAgentPromptBuilder.ParsedReply.needsClarification]）。
 * - 每小时限流 [ConfigRepository.FEEDBACK_AGENT_RATE_LIMIT_PER_HOUR] 次。
 * - 回滚 Action 仅生成预览，不直接应用，需用户在 UI 上点击"确认回滚"后调 [confirmRollback]。
 */
@Singleton
class FeedbackAgent @Inject constructor(
    private val llmRegistry: LlmProviderRegistry,
    private val promptBuilder: FeedbackAgentPromptBuilder,
    private val adjuster: ProfileAdjuster,
    private val versionStore: ProfileVersionStore,
    private val feedbackDao: UserProfileFeedbackDao,
    private val tracker: UserActionTracker,
    private val readAccess: UserProfileReadAccess,
    private val configRepository: ConfigRepository,
    private val gate: ProfilingGate,
) {

    /** 限流串行化：避免并发请求绕过计数。 */
    private val rateMutex = Mutex()

    /**
     * 处理用户消息：解析意图 → 应用非回滚 Action → 生成回滚预览 → 持久化回复。
     *
     * 返回 [AgentReply]；LLM 不可用 / 解析失败时返回降级或澄清回复。
     */
    suspend fun handle(userMessage: String): AgentReply {
        if (!gate.isEnabled()) {
            return degradedReply("画像采集已关闭，无法调校")
        }
        if (!configRepository.isFeedbackAgentEnabled()) {
            return degradedReply("反馈智能体已关闭")
        }
        if (!tryAcquireRate()) {
            return degradedReply("已达每小时调用上限，请稍后再试")
        }

        // 1. 持久化用户消息
        val now = System.currentTimeMillis()
        runCatching {
            feedbackDao.insert(
                UserProfileFeedbackEntity(
                    role = ROLE_USER,
                    content = userMessage,
                    appliedActions = null,
                    rollbackPreviews = null,
                    createdAt = now,
                )
            )
        }.onFailure { Timber.w(it, "持久化用户消息失败") }
        trackFeedbackEvent(UserActionType.FEEDBACK_MESSAGE_SENT, userMessage, now)

        // 2. 构造 prompt 上下文
        val ctx = FeedbackAgentPromptBuilder.AgentContext(
            snapshot = readAccess.latestSnapshot(),
            version = versionStore.activeVersion(),
            activeOverrides = adjuster.activeOverrides(),
            recentFeedback = feedbackDao.recent(RECENT_FEEDBACK_LIMIT).map {
                ProfileMappers.run { it.toSummary() }
            },
            recentVersions = versionStore.recentSummaries(RECENT_VERSIONS_LIMIT),
        )

        // 3. 调 LLM
        val messages = promptBuilder.build(userMessage, ctx)
        val raw = try {
            llmRegistry.getDefaultClient().chatSync(
                messages = messages,
                config = ChatConfig(temperature = 0.3f, maxTokens = 512, jsonMode = true),
            )
        } catch (t: Throwable) {
            Timber.w(t, "FeedbackAgent LLM 调用失败，降级")
            return degradedReply("智能体暂时不可用，请稍后再试或使用快捷调整菜单")
        }

        val parsed = FeedbackAgentPromptBuilder.parse(raw) ?: run {
            val reply = clarifyReply("我没有完全理解你的需求，能否再描述一下？")
            persistAssistantReply(reply)
            return reply
        }
        if (parsed.needsClarification) {
            val reply = clarifyReply(parsed.clarificationQuestion ?: "能否再描述一下你的需求？")
            persistAssistantReply(reply)
            return reply
        }

        // 4. 分流处理 Action
        val rollbackActions = parsed.actions.filterIsInstance<FeedbackAction.RollbackProfileVersion>()
        val otherActions = parsed.actions - rollbackActions.toSet()

        // 4a. 非回滚 Action：即时应用
        val appliedOverrides = if (otherActions.isNotEmpty()) {
            adjuster.applyAll(otherActions, reason = userMessage)
        } else {
            emptyList()
        }
        if (appliedOverrides.isNotEmpty()) {
            trackFeedbackEvent(
                UserActionType.FEEDBACK_OVERRIDE_APPLIED,
                userMessage,
                System.currentTimeMillis(),
                mapOf("count" to JsonPrimitive(appliedOverrides.size)),
            )
        }

        // 4b. 回滚 Action：生成预览（不直接应用，需用户确认）
        val rollbackPreviews = rollbackActions.mapNotNull { versionStore.previewRollback(it) }
        if (rollbackPreviews.isNotEmpty()) {
            trackFeedbackEvent(
                UserActionType.FEEDBACK_VERSION_ROLLBACK_PREVIEW,
                userMessage,
                System.currentTimeMillis(),
                mapOf("count" to JsonPrimitive(rollbackPreviews.size)),
            )
        }

        // 5. 持久化智能体回复
        val reply = AgentReply(
            text = parsed.reply,
            appliedActions = appliedOverrides,
            rollbackPreviews = rollbackPreviews,
            needsClarification = false,
            degraded = false,
        )
        persistAssistantReply(reply)
        return reply
    }

    /** 用户在预览卡片上点击"确认回滚"后调用。 */
    suspend fun confirmRollback(versionId: Long, reason: String): RollbackResult {
        val result = versionStore.applyRollback(versionId, reason = reason)
        trackFeedbackEvent(
            UserActionType.FEEDBACK_VERSION_ROLLBACK_APPLIED,
            reason,
            System.currentTimeMillis(),
            mapOf(
                "fromVersion" to JsonPrimitive(result.fromVersionId),
                "toVersion" to JsonPrimitive(result.toVersionId),
            ),
        )
        return result
    }

    suspend fun confirmRollback(preview: RollbackPreview, reason: String): RollbackResult =
        confirmRollback(preview.targetVersionId, reason)

    // ---- 内部 ----

    private suspend fun tryAcquireRate(): Boolean = rateMutex.withLock {
        val now = System.currentTimeMillis()
        val since = now - 60 * 60 * 1000L
        val count = runCatching { feedbackDao.countSince(since) }.getOrDefault(0)
        // 用户消息 + 智能体回复共享配额，count 翻倍近似（每轮 2 条消息）
        val effectiveCount = count / 2 + 1
        if (effectiveCount > ConfigRepository.FEEDBACK_AGENT_RATE_LIMIT_PER_HOUR) {
            return@withLock false
        }
        true
    }

    private fun trackFeedbackEvent(
        type: UserActionType,
        message: String,
        occurredAt: Long,
        extra: Map<String, JsonElement> = emptyMap(),
    ) {
        runCatching {
            val merged = LinkedHashMap<String, JsonElement>()
            merged["messageLen"] = JsonPrimitive(message.length)
            merged.putAll(extra)
            tracker.trackNow(
                UserActionEvent(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    screen = "ProfileChat",
                    targetId = null,
                    targetKind = null,
                    extra = merged,
                    durationMs = null,
                    occurredAt = occurredAt,
                    session = UUID.randomUUID().toString(),
                )
            )
        }.onFailure { Timber.w(it, "track feedback event failed") }
    }

    private fun clarifyReply(question: String): AgentReply = AgentReply(
        text = question,
        appliedActions = emptyList(),
        rollbackPreviews = emptyList(),
        needsClarification = true,
        clarificationQuestion = question,
        degraded = false,
    )

    private fun degradedReply(message: String): AgentReply = AgentReply(
        text = message,
        appliedActions = emptyList(),
        rollbackPreviews = emptyList(),
        needsClarification = false,
        degraded = true,
    )

    private suspend fun persistAssistantReply(reply: AgentReply) {
        val appliedJson = if (reply.appliedActions.isEmpty()) null
            else ProfileMappers.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(OverrideRecord.serializer()),
                reply.appliedActions,
            )
        val rollbackJson = if (reply.rollbackPreviews.isEmpty()) null
            else ProfileMappers.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(RollbackPreview.serializer()),
                reply.rollbackPreviews,
            )
        runCatching {
            feedbackDao.insert(
                UserProfileFeedbackEntity(
                    role = ROLE_ASSISTANT,
                    content = reply.text,
                    appliedActions = appliedJson,
                    rollbackPreviews = rollbackJson,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }.onFailure { Timber.w(it, "持久化智能体回复失败") }
    }

    private companion object {
        const val ROLE_USER = "USER"
        const val ROLE_ASSISTANT = "ASSISTANT"
        const val RECENT_FEEDBACK_LIMIT = 10
        const val RECENT_VERSIONS_LIMIT = 10
    }
}
