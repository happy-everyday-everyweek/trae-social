package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.AccountIds
import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.data.repository.CommentRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.profiling.mapping.ProfileMappers
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.ChatMessage
import com.trae.social.llm.RulesetEngine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 事件文本预解析器（#146 算法优化）。
 *
 * 核心问题：[UserActionEvent] 不携带文本字段，[BasicProfileAnalyzer] 仅读取
 * extra 中的 imageTheme（预打标字符串）计算兴趣向量，用户实际产生的文本
 * （推文正文、评论内容）从未进入画像建模流水线。
 *
 * 解决方案：在 [BasicProfileTrigger.compute] 分析前，对携带文本的事件
 * （PUBLISH_TWEET / TWEET_COMMENT）按 targetId 回查原文，批量调用 LLM
 * 提取 textTopic / textTopics / textSentiment / textIntent 结构化信号，
 * 写回 event.extra 供 [BasicProfileAnalyzer] 融合消费。
 *
 * 持久化：解析结果通过 [UserActionDao.updateExtra] 写回数据库，
 * 下次分析窗口内重复读取时跳过已解析事件，避免重复消耗 LLM 配额。
 *
 * 容错：LLM 调用失败 / JSON 解析失败时优雅降级，返回原始事件列表，
 * 不阻塞基础分析流程。
 */
@Singleton
class EventTextPreParser @Inject constructor(
    private val rulesetEngine: RulesetEngine,
    private val tweetRepository: TweetRepository,
    private val commentRepository: CommentRepository,
    private val userActionDao: UserActionDao,
    private val configRepository: ConfigRepository,
) {

    /**
     * 对事件列表中携带文本的事件进行 LLM 预解析，提取文本信号写回 extra。
     *
     * 已解析过的事件（extra 中存在 textTopic）跳过；LLM 不可用或调用失败时
     * 优雅降级，返回原始事件列表。
     *
     * 第六轮 review M8 修复：增加整体时间预算 [OVERALL_BUDGET_MS]，避免 200 条事件
     * × 10 批 × 每批 30s 超时 = 5 分钟以上拖垮 UserProfileWorker 10min WM 上限。
     * 超预算后停止处理后续批次，已解析的批次结果仍然写回。
     */
    suspend fun enrichWithTextSignals(events: List<UserActionEvent>): List<UserActionEvent> {
        // 1. 筛选需要解析的事件（携带文本且未解析过）
        val candidates = events.filter { needsParsing(it) }
        if (candidates.isEmpty()) return events

        // 2. 按 targetId 回查原文
        val withText = candidates.mapNotNull { event ->
            val text = retrieveText(event) ?: return@mapNotNull null
            TextBatchItem(event = event, text = text)
        }
        if (withText.isEmpty()) return events

        // 3. 未配置任何端点则跳过（RulesetEngine 内部会抛 IllegalStateException，此处提前短路）
        if (configRepository.listEndpoints().isEmpty()) {
            Timber.w("EventTextPreParser: 未配置任何 LLM 端点，跳过预解析")
            return events
        }

        // 4. 批量 LLM 解析（分批避免单次 token 超限）
        // M8 修复：整体时间预算，避免大批量事件拖垮 Worker 上限
        val enriched = mutableMapOf<String, TextSignals>()
        // Q1 修复：仅收集"批次成功"的候选事件 ID。批次成功（batchParse 未抛异常）时，
        // 无论 LLM 是否返回某 index 的条目，都标记 textParsed=true，避免下个分析窗口
        // 重复送 LLM 消耗配额。批次失败（超时 / 网络异常）时不标记，允许后续重试。
        val parsedIds = mutableSetOf<String>()
        val overallDeadline = System.currentTimeMillis() + OVERALL_BUDGET_MS
        withText.chunked(MAX_BATCH_SIZE).forEach { batch ->
            // M8 修复：超整体预算则停止处理后续批次
            if (System.currentTimeMillis() >= overallDeadline) {
                Timber.w("EventTextPreParser: 超整体时间预算 %dms，停止处理剩余批次（已解析 %d/%d）",
                    OVERALL_BUDGET_MS, parsedIds.size, withText.size)
                return@forEach
            }
            runCatching {
                val result = batchParse(batch)
                // 批次成功：所有该批事件都标记为已解析（即使 LLM 漏返回某 index）
                batch.forEach { parsedIds.add(it.event.id) }
                result.forEach { (eventId, signals) -> enriched[eventId] = signals }
            }.onFailure { Timber.w(it, "EventTextPreParser: 批量解析失败 batch.size=%d", batch.size) }
        }
        if (parsedIds.isEmpty()) return events

        // 5. 写回 extra 并持久化
        return events.map { event ->
            if (event.id !in parsedIds) return@map event
            // LLM 未返回该条目时使用空信号，但仍写 textParsed=true
            val signals = enriched[event.id] ?: TextSignals(
                topic = null,
                topics = emptyList(),
                sentiment = null,
                intent = null,
            )
            val updatedExtra = event.extra.toMutableMap().apply {
                // textParsed 标记：无论 LLM 返回的信号是否为空，都标记为已解析，
                // 避免下次分析窗口内重复调用 LLM（即使 LLM 未能提取 topic 也不重试）
                put(KEY_TEXT_PARSED, JsonPrimitive(true))
                signals.topic?.let { put(KEY_TEXT_TOPIC, JsonPrimitive(it)) }
                if (signals.topics.isNotEmpty()) {
                    put(KEY_TEXT_TOPICS, JsonArray(signals.topics.map { JsonPrimitive(it) }))
                }
                signals.sentiment?.let { put(KEY_TEXT_SENTIMENT, JsonPrimitive(it)) }
                signals.intent?.let { put(KEY_TEXT_INTENT, JsonPrimitive(it)) }
            }
            persistExtra(event.id, updatedExtra)
            event.copy(extra = updatedExtra)
        }
    }

    /**
     * 判断事件是否需要预解析：PUBLISH_TWEET / TWEET_COMMENT 且有 targetId 且未解析过。
     *
     * 第六轮 review 新增 MINOR 修复：排除调度器打标事件（isScenarioMarker=true 或
     * screen ∈ 调度器 screen 白名单），避免把 AI 生成的推文文本（TweetGenerationWorker
     * 为每条 AI 推文打的 PUBLISH_TWEET 事件）送 LLM 解析，浪费配额且污染用户兴趣画像
     * （AI 自生成主题被当作用户主动表达融合进兴趣向量）。
     */
    private fun needsParsing(event: UserActionEvent): Boolean {
        if (event.type != UserActionType.PUBLISH_TWEET &&
            event.type != UserActionType.TWEET_COMMENT
        ) return false
        if (event.targetId.isNullOrBlank()) return false
        // 排除调度器打标事件（与 BasicProfileAnalyzer.B2 修复同策略）
        if (ProfileMappers.readExtraBoolean(event.extra, "isScenarioMarker")) return false
        if (event.screen in SCHEDULER_SCREENS) return false
        // 已解析过则跳过（缓存命中）：检查 textParsed 标记，
        // 而非检查 textTopic（LLM 可能返回 topic=null 但 sentiment/intent 非空）
        return !ProfileMappers.readExtraBoolean(event.extra, KEY_TEXT_PARSED)
    }

    /**
     * 调度器 Worker 落事件的 screen 白名单（与 [BasicProfileAnalyzer.SCHEDULER_SCREENS] 一致）。
     */
    private val SCHEDULER_SCREENS = setOf(
        "tweet_generation",
        "interaction_schedule",
        "interaction_schedule_comment",
        "persona_update_co_evolve",
    )

    /**
     * 按 targetId 回查事件关联的原文。
     *
     * - PUBLISH_TWEET：targetId 为推文 ID，从 tweets 表取 text 字段。
     *   第六轮 review 新增 MINOR 修复：仅回查真实用户发布（authorId == user-self 且
     *   !isAiGenerated）的推文，避免 AI 生成的推文文本被当作用户主动表达送 LLM 解析
     *   （与 needsParsing 的 screen 过滤互为兜底：needsParsing 拦调度器事件，
     *   此处拦截非调度器路径但引用了 AI 推文的边界情况）。
     * - TWEET_COMMENT：优先按 extra 中的 commentId 精确查询（埋点完整性方案），
     *   缺失 commentId 时回退到按推文 + 作者 + 时间最近原则匹配（兼容历史数据）。
     */
    private suspend fun retrieveText(event: UserActionEvent): String? {
        val targetId = event.targetId ?: return null
        return when (event.type) {
            UserActionType.PUBLISH_TWEET -> {
                val tweet = tweetRepository.getById(targetId) ?: return null
                // 仅解析真实用户发布的推文，排除 AI 生成推文
                if (tweet.authorId != AccountIds.USER_SELF_ID || tweet.isAiGenerated) return null
                tweet.text
            }
            UserActionType.TWEET_COMMENT -> {
                // review 修复：优先用埋点写入的 commentId 精确回查，避免多条评论错配
                val commentId = ProfileMappers.readExtraString(event.extra, KEY_COMMENT_ID)
                if (commentId != null) {
                    commentRepository.getById(commentId)?.content
                } else {
                    // 兼容未携带 commentId 的历史事件
                    val comments = commentRepository.getByTweetAndAuthor(targetId, AccountIds.USER_SELF_ID)
                    comments.minByOrNull { abs(it.createdAt - event.occurredAt) }?.content
                }
            }
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    /** 批量调用 LLM 解析文本，返回 eventId → 信号的映射。 */
    private suspend fun batchParse(
        batch: List<TextBatchItem>,
    ): Map<String, TextSignals> {
        val messages = buildPrompt(batch)
        // review 修复：chatSync 是同步阻塞调用，若 LLM hang 住会卡死 BasicProfileTrigger.compute
        // 主路径。包 withTimeout 超时后抛 TimeoutCancellationException，由外层 runCatching 降级。
        val raw = withTimeout(LLM_TIMEOUT_MS) {
            rulesetEngine.chatSync(
                messages = messages,
                config = ChatConfig(temperature = 0.2f, maxTokens = 768, jsonMode = true),
            )
        }
        return parseResponse(raw, batch)
    }

    /** 构造 LLM 提示：system 指令 + user 输入（JSON 数组）。 */
    private fun buildPrompt(batch: List<TextBatchItem>): List<ChatMessage> {
        val system = ChatMessage(
            role = ChatMessage.Role.SYSTEM,
            text = SYSTEM_PROMPT,
        )
        val userContent = buildJsonObject {
            put("events", buildJsonArray {
                batch.forEachIndexed { i, item ->
                    add(buildJsonObject {
                        put("index", i)
                        put("type", item.event.type.name)
                        put("text", item.text)
                    })
                }
            })
        }.toString()
        val user = ChatMessage(role = ChatMessage.Role.USER, text = userContent)
        return listOf(system, user)
    }

    /**
     * 解析 LLM 返回的 JSON 数组，按 index 映射回 eventId。
     *
     * 预期格式：[{ "index": 0, "textTopic": "...", "textTopics": [...],
     *            "textSentiment": "...", "textIntent": "..." }, ...]
     */
    private fun parseResponse(
        raw: String,
        batch: List<TextBatchItem>,
    ): Map<String, TextSignals> {
        val result = mutableMapOf<String, TextSignals>()
        val jsonArray = runCatching {
            ProfileMappers.json.parseToJsonElement(raw).jsonArray
        }.getOrElse {
            Timber.w("EventTextPreParser: LLM 响应非 JSON 数组: %s", raw.take(200))
            return result
        }
        jsonArray.forEach { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: return@forEach
            if (index !in batch.indices) return@forEach
            val signals = TextSignals(
                topic = obj["textTopic"]?.let {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                },
                topics = obj["textTopics"]?.let {
                    runCatching {
                        it.jsonArray.map { el -> el.jsonPrimitive.content }
                    }.getOrDefault(emptyList())
                } ?: emptyList(),
                sentiment = obj["textSentiment"]?.let {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                },
                intent = obj["textIntent"]?.let {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                },
            )
            result[batch[index].event.id] = signals
        }
        return result
    }

    /** 将 enriched extra 编码为 JSON 字符串并持久化到数据库。 */
    private suspend fun persistExtra(eventId: String, extra: Map<String, JsonElement>) {
        val extraStr = if (extra.isEmpty()) {
            ""
        } else {
            ProfileMappers.json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { extra.forEach { (k, v) -> put(k, v) } },
            )
        }
        runCatching { userActionDao.updateExtra(eventId, extraStr) }
            .onFailure { Timber.w(it, "EventTextPreParser: 持久化 extra 失败 id=%s", eventId) }
    }

    /** 单条文本解析输入（事件 + 回查到的原文）。 */
    private data class TextBatchItem(
        val event: UserActionEvent,
        val text: String,
    )

    /** LLM 解析输出的结构化信号。 */
    internal data class TextSignals(
        val topic: String?,
        val topics: List<String>,
        val sentiment: String?,
        val intent: String?,
    )

    internal companion object {
        internal const val KEY_TEXT_PARSED = "textParsed"
        internal const val KEY_TEXT_TOPIC = "textTopic"
        internal const val KEY_TEXT_TOPICS = "textTopics"
        internal const val KEY_TEXT_SENTIMENT = "textSentiment"
        internal const val KEY_TEXT_INTENT = "textIntent"
        /** 评论埋点写入 extra 的评论 ID 键（capture 层写入，预解析层回查）。 */
        internal const val KEY_COMMENT_ID = "commentId"
        private const val MAX_BATCH_SIZE = 20
        /** review 修复：单批 LLM 调用超时上限，超时由 runCatching 降级返回原 events。 */
        private const val LLM_TIMEOUT_MS = 30_000L
        /**
         * 第六轮 review M8 修复：整体时间预算上限。
         *
         * 200 条事件 × 10 批 × 每批 30s 超时 = 5 分钟以上，会拖垮 UserProfileWorker
         * 10min WM 上限（持续负载下 retry → 重处理同一批 → 管线追不上）。
         * 设 2 分钟预算：最多 4 批成功（每批 30s）后停止，剩余批次下个窗口再处理。
         */
        private const val OVERALL_BUDGET_MS = 2 * 60 * 1000L

        private const val SYSTEM_PROMPT =
            "你是一个社交媒体文本分析助手。从用户发布的文本中提取主题、情感和意图。" +
                "输出严格 JSON 数组，每个元素包含 index（与输入对应）、" +
                "textTopic（主主题词，如 摄影/旅行/美食/科技/运动，1-4 字）、" +
                "textTopics（次要主题词数组，0-3 个）、" +
                "textSentiment（positive/neutral/negative）、" +
                "textIntent（share/question/opinion/recommendation）。" +
                "不要输出 JSON 以外的任何文本。"
    }
}
