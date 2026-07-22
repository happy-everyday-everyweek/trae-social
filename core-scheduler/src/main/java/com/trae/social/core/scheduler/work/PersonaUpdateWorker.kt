package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.profiling.capture.SessionManager
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.core.profiling.feedback.FeedbackController
import com.trae.social.core.profiling.feedback.UserProfileReadAccess
import com.trae.social.core.scheduler.ratelimit.SchedulerRateLimiter
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.RulesetEngine
import com.trae.social.llm.interceptor.RateLimitedException
import com.trae.social.llm.prompt.PersonaUpdatePromptBuilder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * 人设动态字段更新 Worker（SubTask 8.4）。
 *
 * 周期按 AI 活跃度档位缩放执行（LOW=14 天 / MEDIUM=7 天 / HIGH=3 天）：
 * 1. 选取 batchSize 个最久未更新的虚拟账号（按档位 10/20/40，#75）；
 * 2. 加载其当前动态字段与最近活动事件；
 * 3. 调 [PersonaUpdatePromptBuilder.build] + rulesetEngine.chatSync；
 * 4. [PersonaUpdatePromptBuilder.parsePersonaUpdate] 解析；
 * 5. [PersonaUpdatePromptBuilder.shouldRollback] 校验（相似度过低则回退）；
 * 6. [AccountRepository.updateDynamicFields] 写入。
 *
 * #146 A/E 场景 7 personaCoEvolve：当 driven 组启用时，注入用户兴趣 Top 主题到
 * 人设演进 prompt，引导虚拟账号人设向用户兴趣方向自然共演化；control 组不注入。
 *
 * RISK-2（人设漂移）：相似度校验确保不会出现人设突变。
 */
@HiltWorker
class PersonaUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val accountRepository: AccountRepository,
    private val tweetRepository: TweetRepository,
    private val rulesetEngine: RulesetEngine,
    private val rateLimiter: SchedulerRateLimiter,
    private val logDao: com.trae.social.core.data.dao.SchedulerLogDao,
    private val configRepository: ConfigRepository,
    // #146 A/E：反哺层场景 7（personaCoEvolve）
    private val feedbackController: FeedbackController,
    private val readAccess: UserProfileReadAccess,
    private val userActionTracker: UserActionTracker,
    private val sessionManager: SessionManager,
) : CoroutineWorker(appContext, params) {

    private val promptBuilder = PersonaUpdatePromptBuilder()

    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        var updated = 0
        var rolledBack = 0
        var failed = 0
        var skipped = 0

        try {
            // IMPL-47：按当前活跃度档位确定批次大小（LOW=10 / MEDIUM=20 / HIGH=40）
            val level = runCatching { configRepository.getAiActivityLevel() }
                .getOrDefault(com.trae.social.core.data.config.AiActivityLevel.MEDIUM)
            val batchSize = level.personaUpdateBatchSize

            // #146 A/E 场景 7 personaCoEvolve：判断本次批次是否 driven（画像驱动人设共演化）。
            // 整批共用一次 driven 判定（而非逐账号），因为人设共演化是批次级策略，
            // 同批要么全注入用户兴趣，要么全不注入，便于 computeFeedbackEffect 做 A/B 回测。
            val sessionId = sessionManager.currentSessionId() ?: "persona_update"
            val drivenScenario7 = feedbackController.shouldApply(7, sessionId)
            val userInterests = if (drivenScenario7) collectUserInterests() else emptyList()

            // 1. 选取 batchSize 个最久未更新的虚拟账号（#75）
            val candidates = pickRandomAccounts(batchSize)
            if (candidates.isEmpty()) {
                logSchedulerEvent("system", started, "no_accounts", null)
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "no_accounts"))
            }

            for (account in candidates) {
                try {
                    // M2 修复：使用带超时的 acquire，避免限流阻塞超过 WorkManager 超时上限
                    if (!rateLimiter.acquireWithTimeout(WorkerConstants.ACQUIRE_TIMEOUT_MS)) {
                        Timber.i("账号 %s 限流等待超时，跳过", account.id)
                        skipped++
                        continue
                    }
                    val success = updateSinglePersona(account, userInterests)
                    when (success) {
                        UpdateResult.UPDATED -> updated++
                        UpdateResult.ROLLED_BACK -> rolledBack++
                        // #112：SKIPPED 是"跳过本次更新"（LLM 临时不可用或 JSON 解析失败），
                        // 不是执行失败，下次周期会重试。独立计数，不计入 failed。
                        UpdateResult.SKIPPED -> skipped++
                    }
                } catch (e: RateLimitedException) {
                    // IMPL-19：429 限流向上抛出，由 doWork 统一捕获并跳过整个批次
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 第六轮 review M3 修复：CancellationException 必须重抛，否则取消信号被吞。
                    throw e
                } catch (t: Throwable) {
                    Timber.w(t, "账号 %s 人设更新失败", account.id)
                    failed++
                }
            }

            // #146 A/E 场景 7：反哺层打标——为本批次人设更新发 scenario 事件，供 computeFeedbackEffect 做 A/B 回测。
            // drivenByProfile 标记本批人设演进是否受画像驱动；control 组同样落事件以便计算共鸣度 delta。
            // 第六轮 review B1/B2 修复：isScenarioMarker=true 标记本事件为调度器打标（非真实用户行为），
            // 供 UserProfileAggregator.computeScenarioStats 区分"曝光标记"与"真实互动"，
            // 供 BasicProfileAnalyzer.analyze 过滤掉调度器打标，避免污染用户画像。
            runCatching {
                userActionTracker.trackNow(
                    UserActionEvent(
                        // 第七轮 review M6 修复：用稳定 id 替代 UUID.randomUUID()。
                        // Worker 重试时 work request id (this.id) 不变，故同一周期内重试
                        // 产生相同 id，Room @PrimaryKey + REPLACE 保证幂等。
                        // 不同周期（PeriodicWorkRequest 下次触发）id 不同，各自一条 marker。
                        id = "marker_s7_${this.id}",
                        type = UserActionType.FEEDBACK_OVERRIDE_APPLIED,
                        screen = "persona_update_co_evolve",
                        targetId = "batch_$started",
                        targetKind = "persona_batch",
                        extra = mapOf(
                            "scenarioId" to kotlinx.serialization.json.JsonPrimitive(7),
                            "drivenByProfile" to kotlinx.serialization.json.JsonPrimitive(drivenScenario7),
                            "group" to kotlinx.serialization.json.JsonPrimitive(if (drivenScenario7) "driven" else "control"),
                            "batchSize" to kotlinx.serialization.json.JsonPrimitive(candidates.size),
                            "updated" to kotlinx.serialization.json.JsonPrimitive(updated),
                            "rolledBack" to kotlinx.serialization.json.JsonPrimitive(rolledBack),
                            "isScenarioMarker" to kotlinx.serialization.json.JsonPrimitive(true),
                        ),
                        occurredAt = started,
                        session = sessionId,
                    )
                )
            }.onFailure { Timber.w(it, "#146 场景 7 打标失败") }

            // #112：日志状态增加 skipped；errorMessage 仅在真正 failed > 0 时设置
            val status = "updated_${updated}_rolledBack_${rolledBack}_skipped_${skipped}_failed_$failed"
            logSchedulerEvent("system", started, status, if (failed > 0) "$failed failed" else null)
            return Result.success(workDataOf(WorkerKeys.KEY_RESULT to status))
        } catch (e: RateLimitedException) {
            // IMPL-19：429 限流直接跳过，不重试，避免浪费配额
            Timber.w("PersonaUpdateWorker 遇到限流，跳过 retryAfter=%s", e.retryAfterSeconds)
            logSchedulerEvent("system", started, "rate_limited", e.message)
            return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "rate_limited"))
        } catch (t: Throwable) {
            // 第六轮 review M3 修复：CancellationException 必须重抛，否则 WorkManager 取消 Worker 时
            // 协程无法正确传播取消信号，导致 doWork 卡在 catch(t: Throwable) 内继续执行返回 Result.retry。
            if (t is kotlinx.coroutines.CancellationException) throw t
            Timber.e(t, "PersonaUpdateWorker 执行失败")
            logSchedulerEvent("system", started, "error", t.message)
            return if (runAttemptCount >= WorkerConstants.MAX_RUN_ATTEMPTS) {
                Result.failure(workDataOf(WorkerKeys.KEY_ERROR to (t.message ?: "unknown")))
            } else {
                Result.retry()
            }
        }
    }

    /**
     * 从虚拟账号中选取 [count] 个。
     *
     * #75：改用最久未更新优先策略，按动态字段 updatedAt 升序选取，
     * 确保长期未更新的账号也能被覆盖，避免纯随机导致的覆盖率不足。
     * 未更新过的账号 updatedAt 为 NULL（最久前），排最前优先更新。
     *
     * m1 修复：原实现先分页加载全部虚拟账号，再在 sortedBy 中逐账号调用
     * `getDynamicFields(account.id)`（N+1 查询，~220 账号 = 220 次单查）。
     * 现改为调用 [AccountRepository.getVirtualAccountsLeastRecentlyUpdated] 单条
     * LEFT JOIN 查询直接由数据库排序并 LIMIT，行为等价但查询数从 O(N) 降为 1。
     */
    private suspend fun pickRandomAccounts(
        count: Int,
    ): List<com.trae.social.core.data.entity.AccountEntity> {
        val accounts = accountRepository.getVirtualAccountsLeastRecentlyUpdated(count)
        Timber.i("pickRandomAccounts: 选取了 %d 个最久未更新的虚拟账号", accounts.size)
        return accounts
    }

    /**
     * 更新单个账号的人设动态字段。
     *
     * @param userInterests 用户兴趣 Top 主题（#146 场景 7 driven 时非空），注入人设演进 prompt。
     */
    private suspend fun updateSinglePersona(
        account: com.trae.social.core.data.entity.AccountEntity,
        userInterests: List<String>,
    ): UpdateResult {
        // 加载当前动态字段
        val dynamic = accountRepository.getDynamicFields(account.id)
        val currentInput = PersonaUpdatePromptBuilder.PersonaDynamicInput(
            lifeStory = dynamic?.lifeStory ?: account.dynamicLifeStory,
            workInfo = dynamic?.workInfo ?: account.dynamicWorkInfo,
            mood = dynamic?.mood ?: account.recentMood,
            relationshipNetwork = (dynamic?.relationshipNetwork ?: emptyList()).joinToString("、"),
        )

        // 收集最近活动事件（最近 5 条推文文本）
        val recentTweets = tweetRepository.getByAuthor(account.id)
            .take(RECENT_EVENTS_LIMIT)
            .map { it.text }
        val recentEvents = recentTweets.ifEmpty { listOf("（暂无近期推文）") }

        // 调用 LLM 生成更新（#146 场景 7：driven 组注入用户兴趣画像）
        val messages = promptBuilder.build(currentInput, recentEvents, userInterests)
        val raw = try {
            rulesetEngine.chatSync(
                messages = messages,
                config = ChatConfig(temperature = 0.7f, maxTokens = 512, jsonMode = true),
            )
        } catch (e: RateLimitedException) {
            // IMPL-19：429 限流向上抛出，由 doWork 统一捕获并跳过
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "账号 %s 人设更新 LLM 调用失败", account.id)
            return UpdateResult.SKIPPED
        }

        val parsed = PersonaUpdatePromptBuilder.parsePersonaUpdate(raw)
            ?: return UpdateResult.SKIPPED

        // 相似度校验：lifeStory / workInfo 任一突变则回退
        val lifeStoryRollback = PersonaUpdatePromptBuilder.shouldRollback(
            currentInput.lifeStory, parsed.lifeStory
        )
        val workInfoRollback = PersonaUpdatePromptBuilder.shouldRollback(
            currentInput.workInfo, parsed.workInfo
        )
        if (lifeStoryRollback || workInfoRollback) {
            Timber.i("账号 %s 人设更新相似度过低，回退", account.id)
            return UpdateResult.ROLLED_BACK
        }

        // 写入更新
        val now = System.currentTimeMillis()
        // #74：relationshipNetwork 改由 LLM 生成（parsed.relationshipNetwork），不再写回旧值
        accountRepository.updateDynamicFields(
            accountId = account.id,
            lifeStory = parsed.lifeStory,
            workInfo = parsed.workInfo,
            relationshipNetwork = parsed.relationshipNetwork,
            mood = parsed.mood,
            updatedAt = now,
        )
        return UpdateResult.UPDATED
    }

    /**
     * 收集用户兴趣 Top 主题（#146 场景 7 personaCoEvolve）。
     *
     * 合并来源：
     * - interestVector：用户兴趣向量 keys（已合并 theme overrides）；
     * - latestSnapshot.evidence.topThemes：观察到的 Top 主题（按 weight 降序）。
     *
     * 任一来源缺失时仅降级，不阻断人设更新。取 Top 10 用于 prompt 提示。
     */
    private fun collectUserInterests(): List<String> {
        val interestVector = runCatching { readAccess.interestVector() }.getOrDefault(emptyMap())
        val snapshot = runCatching { readAccess.latestSnapshot() }.getOrNull()
        val topThemesFromSnapshot = snapshot?.evidence?.topThemes
            ?.sortedByDescending { it.weight }
            ?.take(8)
            ?.map { it.theme }
            ?: emptyList()
        return (topThemesFromSnapshot + interestVector.keys)
            .distinct()
            .take(10)
    }

    // #218：logSchedulerEvent 实现抽到 SchedulerLogger.log，此处保留薄包装统一 action 标识
    private suspend fun logSchedulerEvent(
        accountId: String,
        startedAt: Long,
        status: String,
        error: String?,
    ) {
        SchedulerLogger.log(logDao, "persona_update", accountId, startedAt, status, error)
    }

    private enum class UpdateResult { UPDATED, ROLLED_BACK, SKIPPED }

    private companion object {
        const val RECENT_EVENTS_LIMIT = 5
    }
}
