package com.trae.social.core.profiling.feedback

import com.trae.social.core.data.dao.UserProfileFeedbackDao
import com.trae.social.core.data.entity.UserProfileFeedbackEntity
import com.trae.social.core.data.model.AgentReply
import com.trae.social.core.data.model.FeedbackAction
import com.trae.social.core.data.model.OverrideRecord
import com.trae.social.core.data.model.OverrideType
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
import com.trae.social.llm.prompt.FeedbackIntentParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户反馈智能体（#146 第五层）。
 *
 * **两阶段流水线（算法优化）**：
 * 1. **Stage 1 预解析**（[FeedbackIntentParser]）：轻量 LLM 调用，从用户自然语言提取结构化意图信号
 *    （意图类型 / 实体归一 / 模糊度标记 / 直接动作候选）。把"算法无法直接理解的自然语言"
 *    转换为算法可处理的结构化信号。
 * 2. **Stage 2 主流程**：根据预解析结果分流：
 *    - **直接路径**（[FeedbackIntentParser.ParsedIntent.hasDirectPath]）：高置信度且无需澄清时，
 *      直接应用 [FeedbackIntentParser.ParsedIntent.directActions]，**跳过主 LLM 调用**，节省配额与延迟，
 *      使用模板生成回复。
 *    - **澄清/低置信度路径**：将预解析信号作为附加上下文调主 LLM（[FeedbackAgentPromptBuilder.buildWithPreParse]），
 *      生成澄清问句或最终回复。
 *
 * 安全约束：
 * - 白名单 Action 过滤 + 值域校验（[FeedbackAction.sanitize]）。
 * - LLM 不可用时降级（返回降级菜单）。
 * - 意图模糊时澄清（[FeedbackAgentPromptBuilder.ParsedReply.needsClarification]）。
 * - 每小时限流 [ConfigRepository.FEEDBACK_AGENT_RATE_LIMIT_PER_HOUR] 次。
 * - 回滚 Action 仅生成预览，不直接应用，需用户在 UI 上点击"确认回滚"后调 [confirmRollback]。
 * - 预解析失败时降级到原有单轮主 LLM 流程，保证可用性。
 */
@Singleton
class FeedbackAgent @Inject constructor(
    private val llmRegistry: LlmProviderRegistry,
    private val promptBuilder: FeedbackAgentPromptBuilder,
    private val intentParser: FeedbackIntentParser,
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
     * M-反馈4 修复：已通过限流闸门、但用户消息尚未落盘的并发调用计数（reserve 占位）。
     *
     * 用于补足纯 DB 计数（[UserProfileFeedbackDao.countSince]）的 check-then-act 竞争窗口：
     * 闸门放行后到用户消息落盘前，并发调用不会增加 DB 计数，需以此计数预留配额。
     */
    private val inFlight = AtomicInteger(0)

    /**
     * 处理用户消息：两阶段流水线（预解析 → 直接应用或主 LLM 调用）→ 持久化回复。
     *
     * - **Stage 1 预解析**：调 [FeedbackIntentParser.parse] 提取结构化意图信号。
     * - **Stage 2a 直接路径**：预解析高置信度且无模糊时，直接应用 [FeedbackIntentParser.ParsedIntent.directActions]，
     *   跳过主 LLM 调用，模板生成回复（节省配额、降低延迟）。
     * - **Stage 2b 主 LLM 路径**：预解析模糊/低置信度/含回滚意图时，将预解析信号注入主 prompt
     *   调主 LLM 生成澄清/回复/动作。
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
        // M-反馈4 修复：用户消息已落盘（或持久化失败），DB 计数接管，释放 in-flight 预留占位。
        // 后续调 LLM：配额已由 DB 行持有，LLM 失败亦不退还（符合 reserve 语义）。
        inFlight.decrementAndGet()
        trackFeedbackEvent(UserActionType.FEEDBACK_MESSAGE_SENT, userMessage, now)

        // 2. 构造 Stage-1 预解析上下文 + Stage-2 主 LLM 上下文（共用读取）
        val snapshot = readAccess.latestSnapshot()
        val activeVersion = versionStore.activeVersion()
        val activeOverrides = adjuster.activeOverrides()
        val parseCtx = FeedbackIntentParser.ParseContext(
            availableThemes = collectAvailableThemes(snapshot),
            availablePreferences = collectAvailablePreferences(activeOverrides),
            activeHours = snapshot?.activeHours ?: emptyList(),
            activeScenarioIds = (1..8).filterNot { id ->
                activeOverrides.any { it.type == OverrideType.SCENARIO_DISABLE && it.key == id.toString() }
            },
        )
        val agentCtx = FeedbackAgentPromptBuilder.AgentContext(
            snapshot = snapshot,
            version = activeVersion,
            activeOverrides = activeOverrides,
            recentFeedback = feedbackDao.recent(RECENT_FEEDBACK_LIMIT).map {
                ProfileMappers.run { it.toSummary() }
            },
            recentVersions = versionStore.recentSummaries(RECENT_VERSIONS_LIMIT),
        )

        // 3. Stage 1：预解析
        val client = try {
            llmRegistry.getDefaultClient()
        } catch (t: Throwable) {
            Timber.w(t, "FeedbackAgent 获取 LLM 客户端失败，降级")
            return degradedReply("智能体暂时不可用，请稍后再试或使用快捷调整菜单")
        }
        val parsedIntent = intentParser.parse(client, userMessage, parseCtx)

        // 4. Stage 2a 直接路径：高置信度且无模糊 → 直接应用 directActions，跳过主 LLM 调用
        if (parsedIntent.hasDirectPath) {
            return applyDirectAndReply(userMessage, parsedIntent)
        }

        // 5. Stage 2b 主 LLM 路径：将预解析信号注入主 prompt 调主 LLM
        val messages = promptBuilder.buildWithPreParse(userMessage, agentCtx, parsedIntent)
        val raw = try {
            client.chatSync(
                messages = messages,
                config = ChatConfig(temperature = 0.3f, maxTokens = 512, jsonMode = true),
            )
        } catch (t: Throwable) {
            Timber.w(t, "FeedbackAgent Stage-2 LLM 调用失败，降级")
            // 预解析已给出建议澄清问句时优先复用，避免完全降级
            // 注意：clarificationQuestion 为跨模块 public 属性，Kotlin 无法 smart-cast，
            // 需用局部变量捕获非空值后传递
            val preParseQuestion = parsedIntent.clarificationQuestion
            if (parsedIntent.needsClarification && preParseQuestion != null) {
                val reply = clarifyReply(preParseQuestion)
                persistAssistantReply(reply)
                return reply
            }
            return degradedReply("智能体暂时不可用，请稍后再试或使用快捷调整菜单")
        }

        val parsed = FeedbackAgentPromptBuilder.parse(raw) ?: run {
            // 主 LLM 解析失败时，若预解析已建议澄清问句则复用
            val question = parsedIntent.clarificationQuestion
                ?: "我没有完全理解你的需求，能否再描述一下？"
            val reply = clarifyReply(question)
            persistAssistantReply(reply)
            return reply
        }
        if (parsed.needsClarification) {
            val reply = clarifyReply(parsed.clarificationQuestion ?: "能否再描述一下你的需求？")
            persistAssistantReply(reply)
            return reply
        }

        // 6. 分流处理 Action
        val rollbackActions = parsed.actions.filterIsInstance<FeedbackAction.RollbackProfileVersion>()
        val otherActions = parsed.actions - rollbackActions.toSet()

        // 6a. 非回滚 Action：即时应用
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

        // 6b. 回滚 Action：生成预览（不直接应用，需用户确认）
        val rollbackPreviews = rollbackActions.mapNotNull { versionStore.previewRollback(it) }
        if (rollbackPreviews.isNotEmpty()) {
            trackFeedbackEvent(
                UserActionType.FEEDBACK_VERSION_ROLLBACK_PREVIEW,
                userMessage,
                System.currentTimeMillis(),
                mapOf("count" to JsonPrimitive(rollbackPreviews.size)),
            )
        }

        // 7. 持久化智能体回复
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

    /**
     * Stage-2a 直接路径：应用预解析产出的 directActions，生成模板回复。
     *
     * - 仅应用非回滚 Action（预解析保证 directActions 不含回滚）。
     * - 回滚意图在 detectedIntents 中标记但不在 directActions 中，需走 Stage-2b 生成预览；
     *   但若用户消息仅含回滚意图，directActions 为空 → 不走此路径（hasDirectPath=false）。
     * - 复用 [adjuster.applyAll] 保证缓存失效、软删等逻辑一致。
     */
    private suspend fun applyDirectAndReply(
        userMessage: String,
        parsedIntent: FeedbackIntentParser.ParsedIntent,
    ): AgentReply {
        val applied = adjuster.applyAll(parsedIntent.directActions, reason = userMessage)
        if (applied.isNotEmpty()) {
            trackFeedbackEvent(
                UserActionType.FEEDBACK_OVERRIDE_APPLIED,
                userMessage,
                System.currentTimeMillis(),
                mapOf(
                    "count" to JsonPrimitive(applied.size),
                    "path" to JsonPrimitive("direct"),
                ),
            )
        }
        val reply = AgentReply(
            text = buildDirectReplyText(applied),
            appliedActions = applied,
            rollbackPreviews = emptyList(),
            needsClarification = false,
            degraded = false,
        )
        persistAssistantReply(reply)
        return reply
    }

    /** 直接路径模板回复：避免调用主 LLM，明确告知用户已应用的调整。 */
    private fun buildDirectReplyText(applied: List<OverrideRecord>): String {
        if (applied.isEmpty()) return "已收到你的反馈。"
        val parts = applied.joinToString("；") { record ->
            when (record.type) {
                OverrideType.THEME_BOOST -> "提升主题「${record.key}」权重至 ${record.value}"
                OverrideType.THEME_SUPPRESS -> "压制主题「${record.key}」"
                OverrideType.ADD_PREFERENCE -> "新增偏好「${record.key}」"
                OverrideType.REMOVE_PREFERENCE -> "移除偏好「${record.key}」"
                OverrideType.SCENARIO_DISABLE -> "关闭场景 ${record.key} 反哺"
                OverrideType.CORRECT_NARRATIVE -> "已记录画像叙事修正"
                OverrideType.SET_ACTIVE_HOURS -> "活跃时段调整为 ${record.value}"
            }
        }
        return "已应用 ${applied.size} 项调整：$parts。"
    }

    /** 收集可用主题：画像 topThemes + interestVector keys（去重）。 */
    private fun collectAvailableThemes(
        snapshot: com.trae.social.core.data.model.UserProfileSnapshot?,
    ): List<String> {
        val fromSnapshot = snapshot?.evidence?.topThemes?.map { it.theme } ?: emptyList()
        val fromVector = snapshot?.interestVector?.keys ?: emptyList()
        return (fromSnapshot + fromVector).distinct()
    }

    /** 收集已有偏好：从 activeOverrides 中提取 ADD_PREFERENCE 的 key（未被子集 REMOVE 的）。 */
    private fun collectAvailablePreferences(activeOverrides: List<OverrideRecord>): List<String> {
        val added = activeOverrides
            .filter { it.type == OverrideType.ADD_PREFERENCE }
            .map { it.key }
            .toSet()
        val removed = activeOverrides
            .filter { it.type == OverrideType.REMOVE_PREFERENCE }
            .map { it.key }
            .toSet()
        return (added - removed).toList()
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
        // M-反馈4 修复：reserve 语义——计入 in-flight 未落盘的并发调用，关闭 check-then-act 竞争窗口；
        // 预留本调用配额（+1）在调用 LLM 前即扣除，LLM 失败不退还
        // （用户消息落盘后由 DB 计数接管，随后释放 in-flight 占位）。
        val reservedRounds = count / 2 + inFlight.get()
        if (reservedRounds + 1 > ConfigRepository.FEEDBACK_AGENT_RATE_LIMIT_PER_HOUR) {
            return@withLock false
        }
        inFlight.incrementAndGet() // 预留本调用配额
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
