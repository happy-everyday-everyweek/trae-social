package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trae.social.core.data.entity.InteractionType
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.InteractionRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.scheduler.ratelimit.SchedulerRateLimiter
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.LlmProviderRegistry
import com.trae.social.llm.interceptor.RateLimitedException
import com.trae.social.llm.prompt.CommentPromptBuilder
import com.trae.social.llm.prompt.TweetPromptBuilder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * 待执行互动扫描 Worker（SubTask 8.3 配套）。
 *
 * 周期（15 分钟）扫描 [InteractionRepository.getPendingInteractions]：
 * - COMMENT 类型且 content 为空：调用 LLM 生成评论文本后标记 executedAt；
 * - 其他类型：直接标记 executedAt；
 * - 执行后累加对应 TweetEntity 的 likeCount/commentCount/retweetCount。
 *
 * 该 Worker 与 [InteractionWorker] 配合：后者负责排程，前者负责按计划执行。
 */
@HiltWorker
class PendingInteractionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val interactionRepository: InteractionRepository,
    private val tweetRepository: TweetRepository,
    private val accountRepository: AccountRepository,
    private val llmRegistry: LlmProviderRegistry,
    private val rateLimiter: SchedulerRateLimiter,
    private val logDao: com.trae.social.core.data.dao.SchedulerLogDao,
) : CoroutineWorker(appContext, params) {

    private val commentBuilder = CommentPromptBuilder()

    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        var processed = 0
        var failed = 0

        try {
            // #79：限制单批拉取数量，避免积压时 Worker 执行超时
            val pending = interactionRepository.getPendingInteractions(now, limit = PENDING_BATCH_LIMIT)
            if (pending.isEmpty()) {
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "no_pending"))
            }
            Timber.i("PendingInteractionWorker 扫描到 %d 条待执行互动", pending.size)

            // 按推文分组，便于评论批量生成
            val byTweet = pending.groupBy { it.tweetId }

            for ((tweetId, interactions) in byTweet) {
                val tweet = tweetRepository.getById(tweetId) ?: continue
                val author = accountRepository.getById(tweet.authorId) ?: continue

                // 分离需要生成评论的互动（COMMENT 且 content 为空）
                val needComment = interactions.filter {
                    it.type == InteractionType.COMMENT && it.content.isNullOrBlank()
                }

                // 批量生成评论文本
                val commentTexts: Map<String, String> = if (needComment.isNotEmpty()) {
                    generateCommentsFor(tweet, author, needComment)
                } else {
                    emptyMap()
                }

                // 筛选可执行的互动：COMMENT 必须有内容，其余类型直接执行
                val executable = interactions.filter { interaction ->
                    val content = interaction.content ?: commentTexts[interaction.id]
                    !(interaction.type == InteractionType.COMMENT && content.isNullOrBlank())
                }
                val skipped = interactions.size - executable.size
                failed += skipped
                if (skipped > 0) {
                    Timber.w("推文 %s 有 %d 条评论因内容缺失跳过", tweetId, skipped)
                }

                // IMPL-6：原子地标记执行并累加推文计数（同一 @Transaction）
                if (executable.isNotEmpty()) {
                    try {
                        val executedAt = System.currentTimeMillis()
                        interactionRepository.executeInteractionsAndUpdateTweet(
                            interactions = executable,
                            executedAt = executedAt,
                            tweetId = tweetId,
                        )
                        processed += executable.size
                    } catch (t: Throwable) {
                        Timber.w(t, "原子执行互动批次失败 tweetId=%s", tweetId)
                        failed += executable.size
                    }
                }
            }

            // #115：processed=0 且 failed=0 时为空操作（可能是并发重复扫描），
            // 记录为 no_pending 而非 processed_0_failed_0，避免可观测性指标失真。
            val finalStatus = if (processed == 0 && failed == 0) {
                "no_pending"
            } else {
                "processed_${processed}_failed_${failed}"
            }
            logSchedulerEvent(
                accountId = "system",
                startedAt = started,
                status = finalStatus,
                error = if (failed > 0) "$failed failed" else null,
            )
            return Result.success(
                workDataOf(
                    WorkerKeys.KEY_RESULT to finalStatus,
                    "failed" to failed,
                )
            )
        } catch (e: RateLimitedException) {
            // IMPL-19：429 限流直接跳过，不重试，避免浪费配额
            Timber.w("PendingInteractionWorker 遇到限流，跳过 retryAfter=%s", e.retryAfterSeconds)
            logSchedulerEvent(
                accountId = "system",
                startedAt = started,
                status = "rate_limited",
                error = e.message,
            )
            return Result.success(workDataOf(WorkerKeys.KEY_RESULT to "rate_limited"))
        } catch (t: Throwable) {
            Timber.e(t, "PendingInteractionWorker 执行失败")
            logSchedulerEvent(
                accountId = "system",
                startedAt = started,
                status = "error",
                error = t.message,
            )
            return if (runAttemptCount >= WorkerConstants.MAX_RUN_ATTEMPTS) {
                Result.failure(workDataOf(WorkerKeys.KEY_ERROR to (t.message ?: "unknown")))
            } else {
                Result.retry()
            }
        }
    }

    /**
     * 为一批待评论互动生成评论文本。
     *
     * 返回 Map<interactionId, commentText>。
     */
    private suspend fun generateCommentsFor(
        tweet: com.trae.social.core.data.entity.TweetEntity,
        author: com.trae.social.core.data.entity.AccountEntity,
        interactions: List<com.trae.social.core.data.entity.InteractionEntity>,
    ): Map<String, String> {
        if (interactions.isEmpty()) return emptyMap()
        rateLimiter.acquire()

        // 加载每个评论者的人设
        val personas = mutableListOf<TweetPromptBuilder.PersonaInput>()
        val interactionIds = mutableListOf<String>()
        for (interaction in interactions) {
            val account = accountRepository.getById(interaction.accountId) ?: continue
            personas.add(toPersonaInput(account))
            interactionIds.add(interaction.id)
        }
        if (personas.isEmpty()) return emptyMap()

        val messages = commentBuilder.build(
            tweet = CommentPromptBuilder.TweetInput(
                text = tweet.text,
                authorName = author.displayName,
                authorProfession = author.profession,
            ),
            commenters = personas,
        )
        val raw = try {
            llmRegistry.getDefaultClient().chatSync(
                messages = messages,
                config = ChatConfig(temperature = 0.9f, maxTokens = 512, jsonMode = true),
            )
        } catch (e: RateLimitedException) {
            // IMPL-19：429 限流向上抛出，由 doWork 统一捕获并跳过
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "批量生成评论失败")
            return emptyMap()
        }
        val results = CommentPromptBuilder.parseCommentResults(raw, interactionIds.size)
        val mapping = mutableMapOf<String, String>()
        results.forEach { result ->
            val idx = result.commenterIndex
            if (idx in interactionIds.indices && result.text.isNotBlank()) {
                mapping[interactionIds[idx]] = result.text.take(WorkerConstants.MAX_COMMENT_LENGTH)
            }
        }
        return mapping
    }

    private fun toPersonaInput(
        account: com.trae.social.core.data.entity.AccountEntity,
    ): TweetPromptBuilder.PersonaInput = TweetPromptBuilder.PersonaInput(
        displayName = account.displayName,
        profession = account.profession,
        ageRange = account.ageRange,
        culturalBackground = account.culturalBackground,
        worldview = account.worldview,
        values = account.values,
        languageStyle = account.languageStyle,
        catchphrase = account.catchphrase.joinToString("、"),
        emojiPreference = account.emojiPreference,
        typoRate = account.typoRate,
        recentMood = account.recentMood.ifBlank { "平和" },
    )

    // #218：logSchedulerEvent 实现抽到 SchedulerLogger.log，此处保留薄包装统一 action 标识
    private suspend fun logSchedulerEvent(
        accountId: String,
        startedAt: Long,
        status: String,
        error: String?,
    ) {
        SchedulerLogger.log(logDao, "pending_interaction", accountId, startedAt, status, error)
    }

    private companion object {
        /** #79：单批拉取待执行互动的上限，避免积压时 Worker 执行超时 */
        const val PENDING_BATCH_LIMIT = 50
    }
}
