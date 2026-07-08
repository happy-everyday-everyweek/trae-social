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
import com.trae.social.llm.prompt.PersonaUpdatePromptBuilder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import kotlin.random.Random

/**
 * 人设动态字段更新 Worker（SubTask 8.4）。
 *
 * 周期（7 天）执行：
 * 1. 随机选 20 个虚拟账号；
 * 2. 加载其当前动态字段与最近活动事件；
 * 3. 调 [PersonaUpdatePromptBuilder.build] + LlmClient.chatSync；
 * 4. [PersonaUpdatePromptBuilder.parsePersonaUpdate] 解析；
 * 5. [PersonaUpdatePromptBuilder.shouldRollback] 校验（cosineSimilarity < 0.3 则回退）；
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

        try {
            // IMPL-47：按当前活跃度档位确定批次大小（LOW=10 / MEDIUM=20 / HIGH=40）
            val level = runCatching { configRepository.getAiActivityLevel() }
                .getOrDefault(com.trae.social.core.data.config.AiActivityLevel.MEDIUM)
            val batchSize = level.personaUpdateBatchSize

            // 1. 随机选 batchSize 个虚拟账号
            val candidates = pickRandomAccounts(batchSize)
            if (candidates.isEmpty()) {
                logSchedulerEvent("system", started, "no_accounts", null)
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "no_accounts"))
            }

            for (account in candidates) {
                try {
                    rateLimiter.acquire()
                    val success = updateSinglePersona(account)
                    when (success) {
                        UpdateResult.UPDATED -> updated++
                        UpdateResult.ROLLED_BACK -> rolledBack++
                        UpdateResult.SKIPPED -> failed++
                    }
                } catch (t: Throwable) {
                    Timber.w(t, "账号 %s 人设更新失败", account.id)
                    failed++
                }
            }

            val status = "updated_${updated}_rolledBack_${rolledBack}_failed_$failed"
            logSchedulerEvent("system", started, status, if (failed > 0) "$failed failed" else null)
            return Result.success(workDataOf(WorkerKeys.KEY_RESULT to status))
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
     * 从虚拟账号中随机选取 [count] 个。
     */
    private suspend fun pickRandomAccounts(
        count: Int,
    ): List<com.trae.social.core.data.entity.AccountEntity> {
        val all = mutableListOf<com.trae.social.core.data.entity.AccountEntity>()
        var page = 1
        // 翻页加载虚拟账号，最多翻 5 页避免无限加载
        while (page <= MAX_PAGES) {
            val batch = accountRepository.getAccounts(page)
            if (batch.isEmpty()) break
            all.addAll(batch.filter { it.isVirtual })
            page++
        }
        if (all.isEmpty()) return emptyList()
        return all.shuffled(Random(System.currentTimeMillis())).take(count)
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
        val relationshipList = if (dynamic?.relationshipNetwork.isNullOrEmpty()) {
            emptyList()
        } else {
            dynamic!!.relationshipNetwork
        }
        accountRepository.updateDynamicFields(
            accountId = account.id,
            lifeStory = parsed.lifeStory,
            workInfo = parsed.workInfo,
            relationshipNetwork = relationshipList,
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
        const val MAX_PAGES = 5
    }
}
