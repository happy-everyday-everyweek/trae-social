package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.dao.UserProfileFeedbackDao
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.profiling.analysis.UserProfileAggregator
import com.trae.social.core.profiling.feedback.ProfileVersionStore
import com.trae.social.core.profiling.mapping.ProfileMappers
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.LlmProviderRegistry
import com.trae.social.llm.interceptor.RateLimitedException
import com.trae.social.llm.prompt.UserProfilePromptBuilder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.security.MessageDigest

/**
 * 用户行为建模 LLM 画像 Worker（#146 第三层）。
 *
 * 周期：LOW=96h / MEDIUM=48h / HIGH=24h，networkConstraints。
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
 * 产生新版本：由 [ProfileVersionStore.insertAndActivate] 在单事务内 insert(isActive=false) + 激活,自动取消其他版本 active。
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
    private val llmRegistry: LlmProviderRegistry,
    private val configRepository: ConfigRepository,
    private val logDao: com.trae.social.core.data.dao.SchedulerLogDao,
) : CoroutineWorker(appContext, params) {

    private val promptBuilder = UserProfilePromptBuilder()

    override suspend fun doWork(): Result {
        if (!configRepository.isProfilingEnabled()) {
            return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "profiling_disabled"))
        }
        val started = System.currentTimeMillis()
        try {
            // 1. 加载最新快照
            val snapshot = userProfileDao.latestSnapshot()?.let { ProfileMappers.run { it.toDomain() } }
                ?: run {
                    logEvent(started, "no_snapshot", null)
                    return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "no_snapshot"))
                }

            // 2. 计算输入指纹并检查缓存命中
            val sinceMs = started - FEEDBACK_EFFECT_WINDOW_MS
            val aggregated = aggregator.aggregate(snapshot, sinceMs)
            val latestVersion = userProfileDao.latestVersion()?.let { ProfileMappers.run { it.toDomain() } }
            // M1 修复：指纹剔除 latestVersion.id——版本 id 每次自增会让指纹永远变化，
            // 导致缓存命中短路永远失败，48h 周期 LLM 画像每次重算浪费配额
            val fingerprint = computeFingerprint(snapshot, aggregated)
            val newFeedbackCount = feedbackDao.countSince(latestVersion?.createdAt ?: 0L)
            val hasNewFeedback = newFeedbackCount > 0

            // 短路 1：输入指纹相同 + 无新反馈 → 跳过
            if (latestVersion != null && latestVersion.inputFingerprint == fingerprint && !hasNewFeedback) {
                logEvent(started, "fingerprint_hit", null)
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "fingerprint_hit"))
            }

            // 短路 2：新事件 < 阈值 + 无新反馈 → 跳过
            if (!hasNewFeedback) {
                val newEvents = userActionDao.countSince(latestVersion?.createdAt ?: 0L)
                if (newEvents < MIN_NEW_EVENTS) {
                    logEvent(started, "skip_low_events", "events=$newEvents")
                    return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "skip_low_events"))
                }
            }

            // 3. 构造 prompt + 调 LLM
            val input = UserProfilePromptBuilder.Input(
                snapshot = snapshot,
                eventSummary = aggregated.eventSummary,
                feedbackEffect = aggregated.feedbackEffect,
                userFeedback = aggregated.userFeedback,
                activeOverrides = aggregated.userFeedback.activeOverrides,
                previousNarrative = aggregated.previousNarrative,
            )
            val messages = promptBuilder.build(input)
            // M2 修复：默认 provider 未配置时显式跳过，避免 getDefaultClient 内部异常导致无效 retry
            val provider = configRepository.getDefaultProvider()
            if (provider == null) {
                logEvent(started, "no_default_provider", null)
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "no_default_provider"))
            }
            val raw = try {
                llmRegistry.getClient(provider).chatSync(
                    messages = messages,
                    config = ChatConfig(temperature = 0.6f, maxTokens = 768, jsonMode = true),
                )
            } catch (e: RateLimitedException) {
                Timber.w("UserProfileWorker 遇到限流，跳过 retryAfter=%s", e.retryAfterSeconds)
                logEvent(started, "rate_limited", e.message)
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "rate_limited"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 第七轮 review M5 修复：CancellationException 必须重抛。
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "UserProfileWorker LLM 调用失败")
                logEvent(started, "llm_error", t.message)
                return if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
                    Result.failure(workDataOf(WorkerKeys.KEY_ERROR to (t.message ?: "unknown")))
                } else {
                    Result.retry()
                }
            }

            // 4. 解析
            val parsed = UserProfilePromptBuilder.parseUserProfile(raw) ?: run {
                Timber.w("UserProfileWorker 解析失败，保留旧版本")
                logEvent(started, "parse_failed", raw.take(200))
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "parse_failed"))
            }

            // 5. narrative 突变校验
            val previousNarrative = aggregated.previousNarrative
            if (previousNarrative != null &&
                UserProfilePromptBuilder.shouldRollbackNarrative(previousNarrative, parsed.narrative)
            ) {
                Timber.i("UserProfileWorker narrative 突变，保留旧版本")
                logEvent(started, "narrative_rollback", null)
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "narrative_rollback"))
            }

            // 6. 落库新版本（原子插入并激活，避免双 active 窗口）
            // 第二轮 review Major 2 修复:不再走 insertVersion(isActive=true) + activateNewVersion 两步,
            // 改用 versionStore.insertAndActivate 在单事务内完成 insert(isActive=false) + setActive,
            // 避免中途进程被杀导致 DB 残留两个 isActive=1 记录。
            // 第五轮 review N4 修复:insertVersion 已移入 withTransaction 内,真正实现单事务原子性。
            val now = System.currentTimeMillis()
            val version = com.trae.social.core.data.model.UserProfileVersion(
                id = 0,
                identityHypothesis = parsed.identityHypothesis,
                personalityTraits = parsed.personalityTraits,
                contentPreferences = parsed.contentPreferences,
                socialStyle = parsed.socialStyle,
                activityProfile = parsed.activityProfile,
                engagementLevel = parsed.engagementLevel,
                feedbackWeights = parsed.feedbackWeights,
                narrative = parsed.narrative,
                overrideAcknowledgment = parsed.overrideAcknowledgment,
                modelProvider = provider.id,
                promptHash = promptHash(),
                inputFingerprint = fingerprint,
                snapshotId = null,
                rollbackFrom = null,
                // 由 versionStore.insertAndActivate 在事务内统一激活,此处保持 false
                isActive = false,
                createdAt = now,
            )
            val newId = versionStore.insertAndActivate(version)
            logEvent(started, "success", "versionId=$newId")
            return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "success", "versionId" to newId))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 第七轮 review M5 修复：CancellationException 必须重抛，否则 WorkManager 取消 Worker 时
            // 协程无法正确传播取消信号，导致 doWork 卡在 catch(t: Throwable) 内继续执行返回 Result.retry。
            throw e
        } catch (t: Throwable) {
            Timber.e(t, "UserProfileWorker 执行失败")
            logEvent(started, "error", t.message)
            return if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
                Result.failure(workDataOf(WorkerKeys.KEY_ERROR to (t.message ?: "unknown")))
            } else {
                Result.retry()
            }
        }
    }

    /**
     * 计算输入指纹：hash(snapshot + promptHash + activeOverridesHash + userFeedbackHash + feedbackEffectHash)。
     *
     * M1 修复：剔除 latestVersion.id——它是输出而非输入，每次自增会让指纹永远变化，缓存命中短路失效。
     */
    private fun computeFingerprint(
        snapshot: com.trae.social.core.data.model.UserProfileSnapshot,
        aggregated: UserProfileAggregator.AggregatedInput,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder()
        sb.append(snapshot.computedAt).append('|')
        sb.append(snapshot.evidence.eventCount).append('|')
        sb.append(promptHash()).append('|')
        sb.append(aggregated.userFeedback.activeOverrides.joinToString(",") { "${it.type.id}:${it.key}" }).append('|')
        sb.append(aggregated.userFeedback.recentMessages.take(10).joinToString(",") { "${it.role}:${it.createdAt}" }).append('|')
        sb.append(aggregated.feedbackEffect.scenarioDeltas.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
        md.update(sb.toString().toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    // M4 修复：原 promptHash 仅哈希类名，模板内容变更无法识别；改为类名 + 显式 TEMPLATE_VERSION，
    // 模板变更时递增该常量即可让指纹失效，触发重新生成
    private fun promptHash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("${this::class.java.name}#${PROMPT_TEMPLATE_VERSION}".toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }.take(8)
    }

    // #218：logEvent 实现抽到 SchedulerLogger.log，此处保留薄包装统一 action 标识
    private suspend fun logEvent(startedAt: Long, status: String, error: String?) {
        SchedulerLogger.log(logDao, "user_profile", LOG_ACCOUNT_ID, startedAt, status, error)
    }

    private companion object {
        const val MAX_RUN_ATTEMPTS = 3
        const val MIN_NEW_EVENTS = 20
        const val FEEDBACK_EFFECT_WINDOW_MS = 14L * 24 * 60 * 60 * 1000 // 14 天
        // #146：日志 accountId 用 user-self（真实账号，满足外键约束）
        const val LOG_ACCOUNT_ID = "user-self"
        // M4：prompt 模板版本，模板内容变更时递增以使指纹失效
        const val PROMPT_TEMPLATE_VERSION = 1
    }
}
