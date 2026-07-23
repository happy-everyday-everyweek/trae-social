package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trae.social.core.data.AccountIds
import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.dao.UserProfileFeedbackDao
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.profiling.analysis.UserProfileAggregator
import com.trae.social.core.profiling.feedback.ProfileGenerationService
import com.trae.social.core.profiling.feedback.ProfileVersionStore
import com.trae.social.core.profiling.mapping.ProfileMappers
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.RulesetEngine
import com.trae.social.llm.interceptor.RateLimitedException
import com.trae.social.llm.prompt.UserProfilePromptBuilder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * 用户行为建模 LLM 画像 Worker（#146 第三层）。
 *
 * 周期：LOW=96h / MEDIUM=48h / HIGH=24h，networkConstraints。
 *
 * #311 重构后本 Worker 仅保留薄编排：读配置 → 调 [ProfileGenerationService] /
 * [ProfileVersionStore] / [RulesetEngine] → 返回 Result。指纹计算、narrative 回滚校验、
 * 版本构造等画像领域不变量已下沉到 `core-profiling`，本文件不再持有领域逻辑。
 *
 * 触发前置条件（短路顺序）：
 * 1. 计算输入指纹，与 [com.trae.social.core.data.entity.UserProfileVersionEntity.inputFingerprint]
 *    相同且无新用户反馈 → Result.success() 跳过（缓存命中，复用上次版本）。
 * 2. 否则若自上次画像新事件 < [MIN_NEW_EVENTS] 且无新用户反馈 → Result.success() 跳过。
 * 3. 否则执行 LLM 调用。
 *
 * 429：Result.success() 跳过；其他错误 runAttemptCount >= 3 时 Result.failure()，
 * 否则 Result.retry()。
 *
 * 解析失败：保留旧版本（不产生新版本） + 写 SchedulerLogEntity。
 *
 * 产生新版本：由 [ProfileVersionStore.insertAndActivate] 在单事务内 insert(isActive=false) + 激活,
 * 自动取消其他版本 active。
 * narrative 突变（jaccardSimilarity < 0.4）：保留旧版本 + 写日志。
 */
@HiltWorker
class UserProfileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val userProfileDao: UserProfileDao,
    private val userActionDao: UserActionDao,
    private val feedbackDao: UserProfileFeedbackDao,
    private val aggregator: UserProfileAggregator,
    private val versionStore: ProfileVersionStore,
    private val generationService: ProfileGenerationService,
    private val rulesetEngine: RulesetEngine,
    private val configRepository: ConfigRepository,
    private val logDao: com.trae.social.core.data.dao.SchedulerLogDao,
) : CoroutineWorker(appContext, params) {

    private val promptBuilder = UserProfilePromptBuilder()

    override suspend fun doWork(): Result {
        if (!configRepository.isProfilingEnabled()) {
            return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "profiling_disabled"))
        }
        val started = System.currentTimeMillis()
        return try {
            // 1-3. 加载快照 → 聚合 → 短路检查 → 构造 prompt → 取主端点
            val prep = prepareAndCheckShortCircuits(started)
            // prep 内任一短路命中即返回对应 Result（no_snapshot / fingerprint_hit /
            // skip_low_events / no_endpoint_configured）
            val (aggregated, fingerprint, messages, primaryEndpointId) = when (prep) {
                is PrepareOutcome.Skip -> return prep.result
                is PrepareOutcome.NoSnapshot -> return Result.success(
                    workDataOf(WorkerKeys.KEY_RESULT to "no_snapshot"),
                )
                is PrepareOutcome.Proceed -> prep
            }

            // 4. 调 LLM；rate_limited / llm_error 各有独立 SchedulerLog action 标签
            val raw = when (val llm = callLlm(messages, started)) {
                is LlmOutcome.Skip -> return Result.success(workDataOf(WorkerKeys.KEY_RESULT to llm.status))
                is LlmOutcome.Failure -> return llm.result
                is LlmOutcome.Success -> llm.raw
            }

            // 5. 解析
            val parsed = UserProfilePromptBuilder.parseUserProfile(raw) ?: run {
                Timber.w("UserProfileWorker 解析失败，保留旧版本")
                logEvent(started, "parse_failed", raw.take(200))
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "parse_failed"))
            }

            // 6. narrative 突变校验（领域规则保留在 PromptBuilder，编排留在 Worker）
            // 取局部变量以便 smart cast：previousNarrative 是跨模块公开属性，无法直接 smart cast
            val previousNarrative = aggregated.previousNarrative
            if (previousNarrative != null &&
                UserProfilePromptBuilder.shouldRollbackNarrative(previousNarrative, parsed.narrative)
            ) {
                Timber.i("UserProfileWorker narrative 突变，保留旧版本")
                logEvent(started, "narrative_rollback", null)
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "narrative_rollback"))
            }

            // 7. 落库新版本（原子插入并激活，避免双 active 窗口）
            val now = System.currentTimeMillis()
            val version = generationService.buildNewVersion(parsed, fingerprint, primaryEndpointId, now)
            val newId = versionStore.insertAndActivate(version)
            logEvent(started, "success", "versionId=$newId")
            Result.success(workDataOf(WorkerKeys.KEY_RESULT to "success", "versionId" to newId))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // M5 修复：CancellationException 必须重抛，否则 WorkManager 取消 Worker 时协程无法
            // 正确传播取消信号，doWork 卡在 catch(t: Throwable) 内继续执行返回 Result.retry。
            throw e
        } catch (t: Throwable) {
            Timber.e(t, "UserProfileWorker 执行失败")
            logEvent(started, "error", t.message)
            if (runAttemptCount >= WorkerConstants.MAX_RUN_ATTEMPTS) {
                Result.failure(workDataOf(WorkerKeys.KEY_ERROR to (t.message ?: "unknown")))
            } else {
                Result.retry()
            }
        }
    }

    /** 预处理结果：要么短路返回 [Skip]，要么携带 [Proceed] 供 doWork 调 LLM。 */
    private sealed interface PrepareOutcome {
        data class Proceed(
            val aggregated: UserProfileAggregator.AggregatedInput,
            val fingerprint: String,
            val messages: List<com.trae.social.llm.ChatMessage>,
            val primaryEndpointId: String,
        ) : PrepareOutcome
        data class Skip(val result: Result) : PrepareOutcome
        data object NoSnapshot : PrepareOutcome
    }

    /**
     * 加载快照 → 聚合 → 计算指纹 → 短路检查（fingerprint_hit / skip_low_events）→
     * 构造 prompt → 取主端点（no_endpoint_configured）。
     *
     * 任一短路命中返回 [PrepareOutcome.Skip]，调用方据此直接返回 Result；
     * 快照不存在返回 [PrepareOutcome.NoSnapshot]（外层 doWork 单独处理状态码）。
     */
    private suspend fun prepareAndCheckShortCircuits(started: Long): PrepareOutcome {
        // 1. 加载最新快照
        val snapshot = userProfileDao.latestSnapshot()?.let { ProfileMappers.run { it.toDomain() } }
        if (snapshot == null) {
            logEvent(started, "no_snapshot", null)
            return PrepareOutcome.NoSnapshot
        }

        // 2. 计算输入指纹
        val sinceMs = started - FEEDBACK_EFFECT_WINDOW_MS
        val aggregated = aggregator.aggregate(snapshot, sinceMs)
        val latestVersion = userProfileDao.latestVersion()?.let { ProfileMappers.run { it.toDomain() } }
        val fingerprint = generationService.inputFingerprint(snapshot, aggregated)
        val hasNewFeedback = feedbackDao.countSince(latestVersion?.createdAt ?: 0L) > 0

        // 短路 1：输入指纹相同 + 无新反馈 → 跳过
        if (latestVersion != null && latestVersion.inputFingerprint == fingerprint && !hasNewFeedback) {
            logEvent(started, "fingerprint_hit", null)
            return PrepareOutcome.Skip(
                Result.success(workDataOf(WorkerKeys.KEY_RESULT to "fingerprint_hit")),
            )
        }

        // 短路 2：新事件 < 阈值 + 无新反馈 → 跳过
        if (!hasNewFeedback) {
            val newEvents = userActionDao.countSince(latestVersion?.createdAt ?: 0L)
            if (newEvents < MIN_NEW_EVENTS) {
                logEvent(started, "skip_low_events", "events=$newEvents")
                return PrepareOutcome.Skip(
                    Result.success(workDataOf(WorkerKeys.KEY_RESULT to "skip_low_events")),
                )
            }
        }

        // 3. 构造 prompt
        val input = UserProfilePromptBuilder.Input(
            snapshot = snapshot,
            eventSummary = aggregated.eventSummary,
            feedbackEffect = aggregated.feedbackEffect,
            userFeedback = aggregated.userFeedback,
            activeOverrides = aggregated.userFeedback.activeOverrides,
            previousNarrative = aggregated.previousNarrative,
        )
        val messages = promptBuilder.build(input)

        // M2 修复：未配置任何端点时显式跳过，避免 RulesetEngine.chatSync 内部抛
        // IllegalStateException 后被通用 catch 捕获走 retry 浪费 WorkManager 配额
        val endpoints = configRepository.listEndpoints()
        if (endpoints.isEmpty()) {
            logEvent(started, "no_endpoint_configured", null)
            return PrepareOutcome.Skip(
                Result.success(workDataOf(WorkerKeys.KEY_RESULT to "no_endpoint_configured")),
            )
        }
        return PrepareOutcome.Proceed(aggregated, fingerprint, messages, endpoints.first().id)
    }

    /** LLM 调用结果。 */
    private sealed interface LlmOutcome {
        data class Success(val raw: String) : LlmOutcome
        data class Skip(val status: String) : LlmOutcome
        data class Failure(val result: Result) : LlmOutcome
    }

    /**
     * 调 LLM 并处理 RateLimitedException / CancellationException / 其他 Throwable。
     *
     * - RateLimitedException → [LlmOutcome.Skip]（status=`rate_limited`），调用方返回 success 跳过本次。
     * - 其他 Throwable → [LlmOutcome.Failure]，已根据 `runAttemptCount` 决定 retry/failure。
     *
     * @throws kotlinx.coroutines.CancellationException 协程取消时向上抛
     */
    private suspend fun callLlm(
        messages: List<com.trae.social.llm.ChatMessage>,
        started: Long,
    ): LlmOutcome {
        return try {
            LlmOutcome.Success(
                rulesetEngine.chatSync(
                    messages = messages,
                    config = ChatConfig(temperature = 0.6f, maxTokens = 768, jsonMode = true),
                ),
            )
        } catch (e: RateLimitedException) {
            Timber.w("UserProfileWorker 遇到限流，跳过 retryAfter=%s", e.retryAfterSeconds)
            logEvent(started, "rate_limited", e.message)
            LlmOutcome.Skip("rate_limited")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "UserProfileWorker LLM 调用失败")
            logEvent(started, "llm_error", t.message)
            LlmOutcome.Failure(
                if (runAttemptCount >= WorkerConstants.MAX_RUN_ATTEMPTS) {
                    Result.failure(workDataOf(WorkerKeys.KEY_ERROR to (t.message ?: "unknown")))
                } else {
                    Result.retry()
                },
            )
        }
    }

    // #218：logEvent 实现抽到 SchedulerLogger.log，此处保留薄包装统一 action 标识
    private suspend fun logEvent(startedAt: Long, status: String, error: String?) {
        // #220：统一引用 AccountIds.USER_SELF_ID，与 EventCleanupWorker / PersonaSeeder 一致
        SchedulerLogger.log(logDao, "user_profile", AccountIds.USER_SELF_ID, startedAt, status, error)
    }

    private companion object {
        const val MIN_NEW_EVENTS = 20
        const val FEEDBACK_EFFECT_WINDOW_MS = 14L * 24 * 60 * 60 * 1000 // 14 天
    }
}
