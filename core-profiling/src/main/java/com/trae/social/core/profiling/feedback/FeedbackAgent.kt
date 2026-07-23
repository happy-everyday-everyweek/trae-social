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
import com.trae.social.core.data.util.runCatchingCancellable
import com.trae.social.core.profiling.capture.ProfilingGate
import com.trae.social.core.profiling.capture.SessionManager
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.core.profiling.mapping.ProfileMappers
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.RulesetEngine
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
    private val rulesetEngine: RulesetEngine,
    private val adjuster: ProfileAdjuster,
    private val versionStore: ProfileVersionStore,
    private val feedbackDao: UserProfileFeedbackDao,
    private val tracker: UserActionTracker,
    private val readAccess: UserProfileReadAccess,
    private val configRepository: ConfigRepository,
    private val gate: ProfilingGate,
    private val sessionManager: SessionManager,
) {

    // F3 修复：与其余 4 个 PromptBuilder 约定一致，直接实例化（无状态、无需 DI）。
    // TweetPromptBuilder / CommentPromptBuilder / PersonaUpdatePromptBuilder / UserProfilePromptBuilder
    // 均为普通 class 在各自 Worker 内直接 new，唯独此处曾走构造注入，与既有约定不一致且存在歧义。
    private val promptBuilder = FeedbackAgentPromptBuilder()

    /** 限流串行化：避免并发请求绕过计数。 */
    private val rateMutex = Mutex()

    /**
     * M-反馈4 修复：已通过限流闸门、但用户消息尚未落盘的并发调用占位（reserve 时间戳队列）。
     *
     * 用于补足纯 DB 计数（[UserProfileFeedbackDao.countSince]）的 check-then-act 竞争窗口：
     * 闸门放行后到用户消息落盘前，并发调用不会增加 DB 计数，需以此计数预留配额。
     *
     * 第二轮 review Minor 7 修复:旧实现用 `AtomicInteger` 无时间窗口,持久化持续失败时
     * 占位永不释放,连续 10 次失败后 `inFlight=10`,限流永久阻塞直到应用重启。
     * 改为 `ConcurrentLinkedQueue<Long>` 存占位时间戳:
     * - 持久化成功时 poll() 移除一个占位(DB 计数接管)
     * - 持久化失败时保留占位(保守降级,符合上轮 review)
     * - `tryAcquireRate` 每次先清理超过 1h(与限流窗口一致)的过期占位,避免永久锁死
     */
    private val inFlightTimestamps = java.util.concurrent.ConcurrentLinkedQueue<Long>()

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
        val persisted = runCatchingCancellable {
            feedbackDao.insert(
                UserProfileFeedbackEntity(
                    role = ROLE_USER,
                    content = userMessage,
                    appliedActions = null,
                    rollbackPreviews = null,
                    createdAt = now,
                )
            )
        }.onFailure { Timber.w(it, "持久化用户消息失败") }.isSuccess
        // review 修复：仅在持久化成功时释放 in-flight 预留占位（DB 计数接管）。
        // 持久化失败时保留占位，避免 count 与 inFlight 双双漏掉本次调用而绕过限流；
        // 持续失败会令占位累积并触发限流，符合"DB 抖动时保守降级"的安全语义。
        // 第二轮 review Minor 7 修复:占位带时间戳,超过 1h(限流窗口)后由 tryAcquireRate
        // 自动清理,避免持续失败导致限流永久锁死。
        if (persisted) {
            inFlightTimestamps.poll()
        }
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
        // 第七轮 review M3 修复：chatSync 是同步阻塞调用，若 LLM hang 住会卡死整个用户反馈
        // 流程（协程永久挂起）。包 withTimeout 超时后抛 TimeoutCancellationException，
        // 由下面的 catch (t: Throwable) 降级为 degradedReply。与 EventTextPreParser 一致。
        val raw = try {
            kotlinx.coroutines.withTimeout(LLM_TIMEOUT_MS) {
                rulesetEngine.chatSync(
                    messages = messages,
                    config = ChatConfig(temperature = 0.3f, maxTokens = 512, jsonMode = true),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 第六轮 review M3 修复：CancellationException 必须重抛，否则协程取消信号被吞，
            // 调用方（如 ViewModelScope 取消）无法正确传播，导致 FeedbackAgent 协程泄漏。
            // 注意：TimeoutCancellationException 是 CancellationException 子类，withTimeout
            // 超时时抛出 TimeoutCancellationException → 命中此分支被重抛 → 调用方拿不到降级 reply。
            // 因此需在重抛前判断是否为超时（TimeoutCancellationException），若是则走降级路径。
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                Timber.w(e, "FeedbackAgent LLM 调用超时 %dms，降级", LLM_TIMEOUT_MS)
                val reply = degradedReply("智能体响应超时，请稍后再试或使用快捷调整菜单")
                persistAssistantReply(reply)
                return reply
            }
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "FeedbackAgent LLM 调用失败，降级")
            // 第五轮 review N1 修复:LLM 失败时 USER 消息已入库,若降级回复不落盘,
            // 历史里会出现"用户发言无回复"的断档。其余出口(parse 失败 / needsClarification /
            // 正常路径)均已 persistAssistantReply,此处补齐,保证每条 USER 消息都有对应 ASSISTANT 回复。
            val reply = degradedReply("智能体暂时不可用，请稍后再试或使用快捷调整菜单")
            persistAssistantReply(reply)
            return reply
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
        // 第二轮 review Minor 7 修复:先清理 in-flight 中超过 1h(限流窗口)的过期占位,
        // 避免持久化持续失败时占位累积触发限流永久锁死。FIFO 队列按时间戳升序,
        // peek/poll 即可清理最早过期项。
        while (true) {
            val oldest = inFlightTimestamps.peek() ?: break
            if (oldest < since) {
                inFlightTimestamps.poll()
            } else {
                break
            }
        }
        // review 修复：精确统计 USER 消息数作为 round 计数，移除 count/2 近似。
        // 每轮对话恰为 1 条 USER 消息，ASSISTANT 回复持久化失败不影响 USER 计数准确性。
        val userCount = runCatchingCancellable { feedbackDao.countByRoleSince(since, ROLE_USER) }.getOrDefault(0)
        // reserve 语义：计入 in-flight 未落盘的并发调用，关闭 check-then-act 竞争窗口；
        // 预留本调用配额（+1）在调用 LLM 前即扣除，LLM 失败不退还
        // （用户消息落盘后由 DB 计数接管，随后释放 in-flight 占位；持久化失败则保留占位,
        //   但 1h 后会被 tryAcquireRate 自动清理）。
        val reservedRounds = userCount + inFlightTimestamps.size
        if (reservedRounds + 1 > ConfigRepository.FEEDBACK_AGENT_RATE_LIMIT_PER_HOUR) {
            return@withLock false
        }
        inFlightTimestamps.add(now) // 预留本调用配额(带时间戳)
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
            // 第五轮 review N2 修复:复用 SessionManager.currentSessionId(),与全仓其余埋点
            // (经 UserActionEventBuilder.emit 走 sessionProvider())一致。旧实现每条反馈事件
            // 各用一个随机 UUID 作为 session,会被按 session 聚合的分析层当作独立会话,污染会话级指标。
            // currentSessionId() 为 null(进程刚启动未 onResume)时回退到新 UUID,与 SessionManager
            // 首次未 resume 的语义一致。
            val session = sessionManager.currentSessionId() ?: UUID.randomUUID().toString()
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
                    session = session,
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
        runCatchingCancellable {
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
        /** 第七轮 review M3 修复：LLM 调用超时上限，超时由 catch 降级为 degradedReply。 */
        const val LLM_TIMEOUT_MS = 30_000L
    }
}
