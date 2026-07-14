package com.trae.social.core.scheduler.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.InteractionType
import com.trae.social.core.data.entity.SchedulerLogEntity
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
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 互动排程 Worker（SubTask 8.3）。
 *
 * 当一条新推文写入后，由调度器入队本 Worker：
 * 1. 加载被评推文与作者；
 * 2. 从虚拟账号池中按人设相似度（bio 关键词 + 职业重合）筛选 3-8 个评论者；
 * 3. 按概率分配互动类型（LIKE 50% / COMMENT 30% / RETWEET 15% / FOLLOW 5%）；
 * 4. 按对数正态分布为每条互动排程延迟触发时刻；
 * 5. 评论者批量调用一次 LLM 生成评论文本；
 * 6. 写 [InteractionEntity]（scheduledAt = now + delay）；
 * 7. 更新推文计数（最终计数由 [PendingInteractionWorker] 在执行时累加）。
 *
 * 注意：本 Worker 仅负责"即时排程"，实际延迟执行由 [PendingInteractionWorker] 周期扫描完成。
 */
@HiltWorker
class InteractionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val accountRepository: AccountRepository,
    private val tweetRepository: TweetRepository,
    private val interactionRepository: InteractionRepository,
    private val llmRegistry: LlmProviderRegistry,
    private val rateLimiter: SchedulerRateLimiter,
    private val logDao: com.trae.social.core.data.dao.SchedulerLogDao,
) : CoroutineWorker(appContext, params) {

    private val commentBuilder = CommentPromptBuilder()

    override suspend fun doWork(): Result {
        val tweetId = inputData.getString(WorkerKeys.KEY_TWEET_ID)
        if (tweetId.isNullOrBlank()) {
            return Result.failure(workDataOf(WorkerKeys.KEY_ERROR to "missing tweetId"))
        }
        val started = System.currentTimeMillis()
        var status = "success"
        var error: String? = null

        try {
            // 1. 查推文与作者
            val tweet = tweetRepository.getById(tweetId)
            if (tweet == null) {
                Timber.w("推文 %s 不存在，跳过互动排程", tweetId)
                status = "skipped_no_tweet"
                logSchedulerEvent(tweetId, started, status, "tweet not found")
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to status))
            }
            val author = accountRepository.getById(tweet.authorId)
            if (author == null) {
                status = "skipped_no_author"
                logSchedulerEvent(tweet.authorId, started, status, "author not found")
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to status))
            }

            // 2. 选评论者：从虚拟账号中按相似度筛选
            val candidates = selectCommenters(tweet.authorId, author.profession, author.bio)
            if (candidates.isEmpty()) {
                status = "skipped_no_commenters"
                logSchedulerEvent(tweet.authorId, started, status, null)
                return Result.success(workDataOf(WorkerKeys.KEY_RESULT to status))
            }

            // 3. 分配互动类型
            val now = System.currentTimeMillis()
            // #116：使用 nanoTime + accountId.hashCode() 作为种子，
            // 避免毫秒级种子在并发时产生相同互动模式
            val random = Random(System.nanoTime() xor tweet.authorId.hashCode().toLong())
            val assignments = candidates.map { account ->
                val type = assignInteractionType(random)
                val delayMillis = scheduleDelayFor(type, random)
                InteractionAssignment(
                    accountId = account.id,
                    type = type,
                    delayMillis = delayMillis,
                    persona = toPersonaInput(account),
                )
            }

            // 4. 评论批量化：收集需评论者，一次调用 LLM
            val commentAssignments = assignments.filter { it.type == InteractionType.COMMENT }
            val commentTextsByAccount: Map<String, String> = if (commentAssignments.isNotEmpty()) {
                generateComments(tweet, author, commentAssignments, random)
            } else {
                emptyMap()
            }

            // 5. 写 InteractionEntity（排程）
            val interactions = assignments.map { assignment ->
                InteractionEntity(
                    id = UUID.randomUUID().toString(),
                    tweetId = tweet.id,
                    accountId = assignment.accountId,
                    type = assignment.type,
                    content = commentTextsByAccount[assignment.accountId],
                    createdAt = now,
                    scheduledAt = now + assignment.delayMillis,
                    executedAt = null,
                )
            }
            interactionRepository.scheduleInteractions(interactions)

            // #78：短延迟的 LIKE/FOLLOW/COMMENT/RETWEET 用 OneTimeWorkRequest + setInitialDelay
            // 直接调度，避免受 PendingInteractionWorker 15 分钟周期限制，使互动更像真人即时反应
            val shortDelayInteractions = interactions.filter {
                it.type in setOf(
                    InteractionType.LIKE,
                    InteractionType.FOLLOW,
                    InteractionType.COMMENT,
                    InteractionType.RETWEET,
                )
            }.filter {
                (it.scheduledAt - now) <= SHORT_DELAY_THRESHOLD_MS
            }
            if (shortDelayInteractions.isNotEmpty()) {
                enqueueImmediateInteractionExecution(shortDelayInteractions, now)
            }

            // 6. 写调度日志
            status = "scheduled_${interactions.size}"
            logSchedulerEvent(tweet.authorId, started, status, null)
            return Result.success(
                workDataOf(
                    WorkerKeys.KEY_RESULT to status,
                    WorkerKeys.KEY_TWEET_ID to tweet.id,
                )
            )
        } catch (e: RateLimitedException) {
            // IMPL-19：429 限流直接跳过，不重试，避免浪费配额
            Timber.w("InteractionWorker 遇到限流，跳过 tweetId=%s retryAfter=%s", tweetId, e.retryAfterSeconds)
            status = "rate_limited"
            logSchedulerEvent(tweetId, started, status, e.message)
            return Result.success(workDataOf(WorkerKeys.KEY_RESULT to status))
        } catch (t: Throwable) {
            Timber.e(t, "InteractionWorker 执行失败 tweetId=%s", tweetId)
            error = t.message ?: t.javaClass.simpleName
            status = "error"
            logSchedulerEvent(tweetId, started, status, error)
            return if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
                Result.failure(workDataOf(WorkerKeys.KEY_ERROR to error))
            } else {
                Result.retry()
            }
        }
    }

    /**
     * 从虚拟账号中按人设相似度筛选 3-8 个评论者。
     *
     * 翻页加载全部虚拟账号（约 220 个），按 bio 关键词 + 职业重合度评分，
     * 取相似度 Top targetCount 个作为评论者，打乱顺序以随机化各账号分配到的互动类型
     * （LIKE/COMMENT/RETWEET/FOLLOW）。候选不足时回退到全量随机选取。
     */
    private suspend fun selectCommenters(
        authorId: String,
        authorProfession: String,
        authorBio: String,
    ): List<com.trae.social.core.data.entity.AccountEntity> {
        // #106：移除 MAX_ACCOUNT_PAGES 硬编码上限，循环直到无更多数据
        val all = mutableListOf<com.trae.social.core.data.entity.AccountEntity>()
        var page = 1
        while (true) {
            val batch = runCatching { accountRepository.getAccounts(page) }.getOrDefault(emptyList())
            if (batch.isEmpty()) break
            all.addAll(batch.filter { it.isVirtual && it.id != authorId })
            page++
        }
        if (all.isEmpty()) return emptyList()

        val authorKeywords = extractKeywords(authorBio + " " + authorProfession)
        val scored = all.map { account ->
            val overlap = countOverlap(authorKeywords, extractKeywords(account.bio + " " + account.profession))
            val professionMatch = if (account.profession == authorProfession) 2 else 0
            account to (overlap + professionMatch)
        }
        // #82：按相似度分数降序选取。targetCount = min(MAX, max(MIN, 全量 30%))，
        // 即受 MIN/MAX 约束的相似度 Top targetCount 个。
        val targetCount = min(MAX_COMMENTERS, max(MIN_COMMENTERS, (all.size * 0.3).toInt().coerceAtLeast(1)))
        // m2 修复：原 pool.shuffled().take(targetCount) 中 pool.size == targetCount，
        // take 为冗余空操作已移除；shuffled 保留用于打乱评论者顺序，进而随机化
        // 各账号在后续 assignInteractionType 中分到的互动类型。
        return scored.sortedByDescending { it.second }
            .take(targetCount)
            .map { it.first }
            .shuffled(Random(System.currentTimeMillis()))
    }

    private fun extractKeywords(text: String): Set<String> {
        return text.split(Regex("[\\s,，。、；;:：!！?？]+"))
            .filter { it.isNotBlank() && it.length >= 2 }
            .map { it.lowercase() }
            .toSet()
    }

    private fun countOverlap(a: Set<String>, b: Set<String>): Int = a.intersect(b).size

    /**
     * 按概率分配互动类型。
     */
    private fun assignInteractionType(random: Random): InteractionType {
        val r = random.nextDouble()
        return when {
            r < LIKE_THRESHOLD -> InteractionType.LIKE
            r < LIKE_THRESHOLD + COMMENT_THRESHOLD -> InteractionType.COMMENT
            r < LIKE_THRESHOLD + COMMENT_THRESHOLD + RETWEET_THRESHOLD -> InteractionType.RETWEET
            else -> InteractionType.FOLLOW
        }
    }

    /**
     * 按对数正态分布生成互动延迟（毫秒）（IMPL-20）。
     *
     * 使用 nextGaussian()*std+mean 在 log 空间采样后取指数，
     * 再 clamp 到 [minMs, maxMs]。
     *
     * - LIKE：30s - 5min
     * - COMMENT：2min - 15min
     * - RETWEET：5min - 30min
     * - FOLLOW：1min - 10min
     */
    private fun scheduleDelayFor(type: InteractionType, random: Random): Long {
        val (minMs, maxMs) = when (type) {
            InteractionType.LIKE -> 30_000L to 5 * 60_000L
            InteractionType.COMMENT -> 2 * 60_000L to 15 * 60_000L
            InteractionType.RETWEET -> 5 * 60_000L to 30 * 60_000L
            InteractionType.FOLLOW -> 60_000L to 10 * 60_000L
        }
        val logMin = ln(minMs.toDouble())
        val logMax = ln(maxMs.toDouble())
        val mean = (logMin + logMax) / 2
        val std = (logMax - logMin) / 4
        // IMPL-20：用 Box-Muller 变换实现真正的对数正态分布
        // kotlin.random.Random 无 nextGaussian()，用均匀随机数通过 Box-Muller 变换生成
        val u1 = random.nextDouble().coerceAtLeast(Double.MIN_VALUE)
        val u2 = random.nextDouble()
        val gaussian = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
            kotlin.math.cos(2.0 * kotlin.math.PI * u2)
        val logValue = mean + std * gaussian
        var raw = exp(logValue).toLong()
        if (raw < minMs) raw = minMs
        if (raw > maxMs) raw = maxMs
        return raw
    }

    /**
     * 批量生成评论：一次 LLM 调用为所有评论者生成评论文本。
     */
    private suspend fun generateComments(
        tweet: com.trae.social.core.data.entity.TweetEntity,
        author: com.trae.social.core.data.entity.AccountEntity,
        commenters: List<InteractionAssignment>,
        random: Random,
    ): Map<String, String> {
        if (commenters.isEmpty()) return emptyMap()
        rateLimiter.acquire()

        val personas = commenters.map { it.persona }
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
            // IMPL-19：429 限流向上抛出，由 doWork 统一捕获并跳过，不在此处吞掉
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "批量生成评论失败，跳过评论内容")
            return emptyMap()
        }
        val results = CommentPromptBuilder.parseCommentResults(raw, commenters.size)
        val mapping = mutableMapOf<String, String>()
        results.forEach { result ->
            val idx = result.commenterIndex
            if (idx in commenters.indices && result.text.isNotBlank()) {
                mapping[commenters[idx].accountId] = result.text.take(MAX_COMMENT_LENGTH)
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
                    action = "interaction_schedule",
                    result = status,
                    durationMs = System.currentTimeMillis() - startedAt,
                    errorMessage = error,
                )
            )
        }.onFailure { Timber.w(it, "写调度日志失败") }
    }

    private data class InteractionAssignment(
        val accountId: String,
        val type: InteractionType,
        val delayMillis: Long,
        val persona: TweetPromptBuilder.PersonaInput,
    )

    /**
     * IMPL-21：为短延迟的互动入队即时执行 Worker。
     *
     * PendingInteractionWorker 是 15 分钟周期，LIKE 设计延迟 30s-5min 会最坏延迟到 20min。
     * #78：COMMENT/RETWEET 也纳入即时执行，阈值放宽到 30min 覆盖其延迟区间。
     * 此处为短延迟互动入队 OneTimeWorkRequest + setInitialDelay，由 WorkManager 精确触发。
     */
    private fun enqueueImmediateInteractionExecution(
        interactions: List<InteractionEntity>,
        now: Long,
    ) {
        runCatching {
            val workManager = androidx.work.WorkManager.getInstance(applicationContext)
            for (interaction in interactions) {
                val delayMs = (interaction.scheduledAt - now).coerceAtLeast(0L)
                val request = androidx.work.OneTimeWorkRequestBuilder<PendingInteractionWorker>()
                    .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .setConstraints(WorkerPolicies.networkConstraints)
                    .addTag(WorkerTags.INTERACTION)
                    .build()
                // #71：使用 enqueueUniqueWork 避免同一互动重复入队
                workManager.enqueueUniqueWork(
                    "pending_interaction_${interaction.id}",
                    androidx.work.ExistingWorkPolicy.KEEP,
                    request,
                )
            }
            Timber.i("IMPL-21: 为 %d 个短延迟互动入队即时执行", interactions.size)
        }.onFailure { Timber.w(it, "即时互动入队失败，回退到周期执行") }
    }

    private companion object {
        const val MAX_RUN_ATTEMPTS = 3
        const val MIN_COMMENTERS = 3
        const val MAX_COMMENTERS = 8
        const val MAX_COMMENT_LENGTH = 100
        const val LIKE_THRESHOLD = 0.50
        const val COMMENT_THRESHOLD = 0.30
        const val RETWEET_THRESHOLD = 0.15
        /** #78：短延迟阈值，覆盖 COMMENT(<=15min)/RETWEET(<=30min)，低于此值的互动用 OneTimeWorkRequest 直接调度 */
        const val SHORT_DELAY_THRESHOLD_MS = 30L * 60L * 1000L
    }
}
