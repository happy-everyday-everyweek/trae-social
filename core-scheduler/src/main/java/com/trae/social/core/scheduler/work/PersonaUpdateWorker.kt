package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trae.social.core.data.entity.PersonaDynamicFieldEntity
import com.trae.social.core.data.entity.SchedulerLogEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.scheduler.ratelimit.SchedulerRateLimiter
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.LlmProviderRegistry
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
 * 3. 调 [PersonaUpdatePromptBuilder.build] + LlmClient.chatSync；
 * 4. [PersonaUpdatePromptBuilder.parsePersonaUpdate] 解析；
 * 5. [PersonaUpdatePromptBuilder.shouldRollback] 校验（相似度过低则回退）；
 * 6. [AccountRepository.updateDynamicFields] 写入。
 *
 * RISK-2（人设漂移）：相似度校验确保不会出现人设突变。
 */
@HiltWorker
class PersonaUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val accountRepository: AccountRepository,
    private val tweetRepository: TweetRepository,
    private val llmRegistry: LlmProviderRegistry,
    private val rateLimiter: SchedulerRateLimiter,
    private val logDao: com.trae.social.core.data.dao.SchedulerLogDao,
    private val configRepository: ConfigRepository,
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

            // 1. 选取 batchSize 个最久未更新的虚拟账号（#75）
            val candidates = pickRandomAccounts(batchSize)
            if (candidates.isEmpty()) {
                logSchedulerEvent("system", started, "no_accounts", null)
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "no_accounts"))
            }

            for (account in candidates) {
                try {
                    // M2 修复：使用带超时的 acquire，避免限流阻塞超过 WorkManager 超时上限
                    if (!rateLimiter.acquireWithTimeout(ACQUIRE_TIMEOUT_MS)) {
                        Timber.i("账号 %s 限流等待超时，跳过", account.id)
                        skipped++
                        continue
                    }
                    val success = updateSinglePersona(account)
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
                } catch (t: Throwable) {
                    Timber.w(t, "账号 %s 人设更新失败", account.id)
                    failed++
                }
            }

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
            Timber.e(t, "PersonaUpdateWorker 执行失败")
            logSchedulerEvent("system", started, "error", t.message)
            return if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
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
     * 未更新过的账号 updatedAt 为 0（最久前），排最前优先更新。
     */
    private suspend fun pickRandomAccounts(
        count: Int,
    ): List<com.trae.social.core.data.entity.AccountEntity> {
        val all = mutableListOf<com.trae.social.core.data.entity.AccountEntity>()
        var page = 1
        // #106：移除 MAX_PAGES 硬编码上限，循环直到无更多数据，
        // 避免账号数超过 240 时静默丢弃部分账号。
        while (true) {
            val batch = accountRepository.getAccounts(page)
            if (batch.isEmpty()) break
            all.addAll(batch.filter { it.isVirtual })
            page++
        }
        if (all.isEmpty()) return emptyList()
        Timber.i("pickRandomAccounts: 加载了 %d 个虚拟账号", all.size)
        // #75：优先选取最久未更新的账号（按动态字段 updatedAt 升序），
        // 未更新过的账号 getDynamicFields 返回 null，视为 0L 排最前
        return all.sortedBy { account ->
            runCatching {
                accountRepository.getDynamicFields(account.id)?.updatedAt ?: 0L
            }.getOrDefault(0L)
        }.take(count)
    }

    /**
     * 更新单个账号的人设动态字段。
     */
    private suspend fun updateSinglePersona(
        account: com.trae.social.core.data.entity.AccountEntity,
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

        // 调用 LLM 生成更新
        val messages = promptBuilder.build(currentInput, recentEvents)
        val raw = try {
            llmRegistry.getDefaultClient().chatSync(
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

    private suspend fun logSchedulerEvent(
        accountId: String,
        startedAt: Long,
        status: String,
        error: String?,
    ) {
        runCatching {
            logDao.insert(
                SchedulerLogEntity(
                    timestamp = System.currentTimeMillis(),
                    accountId = accountId,
                    action = "persona_update",
                    result = status,
                    durationMs = System.currentTimeMillis() - startedAt,
                    errorMessage = error,
                )
            )
        }.onFailure { Timber.w(it, "写调度日志失败") }
    }

    private enum class UpdateResult { UPDATED, ROLLED_BACK, SKIPPED }

    private companion object {
        const val MAX_RUN_ATTEMPTS = 3
        const val RECENT_EVENTS_LIMIT = 5
        /** M2 修复：限流等待超时（8 分钟，低于 WorkManager 默认 10 分钟超时） */
        const val ACQUIRE_TIMEOUT_MS = 8 * 60 * 1000L
    }
}
