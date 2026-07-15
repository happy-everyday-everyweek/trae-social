package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.data.repository.CommentRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.profiling.mapping.ProfileMappers
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.ChatMessage
import com.trae.social.llm.LlmProviderRegistry
import kotlinx.serialization.json.JsonArray
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
    private val llmRegistry: LlmProviderRegistry,
    private val tweetRepository: TweetRepository,
    private val commentRepository: CommentRepository,
    private val userActionDao: UserActionDao,
    private val configRepository: ConfigRepository,
) {

    /** 当前用户账号 ID，与 FeedViewModel.USER_SELF_ID 一致。 */
    private val userSelfId = "user-self"

    /**
     * 对事件列表中携带文本的事件进行 LLM 预解析，提取文本信号写回 extra。
     *
     * 已解析过的事件（extra 中存在 textTopic）跳过；LLM 不可用或调用失败时
     * 优雅降级，返回原始事件列表。
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

        // 3. 获取 LLM provider，未配置则跳过
        val provider = configRepository.getDefaultProvider() ?: run {
            Timber.w("EventTextPreParser: 默认 LLM provider 未配置，跳过预解析")
            return events
        }

        // 4. 批量 LLM 解析（分批避免单次 token 超限）
        val enriched = mutableMapOf<String, TextSignals>()
        withText.chunked(MAX_BATCH_SIZE).forEach { batch ->
            runCatching {
                batchParse(batch, provider).forEach { (eventId, signals) ->
                    enriched[eventId] = signals
                }
            }.onFailure { Timber.w(it, "EventTextPreParser: 批量解析失败 batch.size=%d", batch.size) }
        }
        if (enriched.isEmpty()) return events

        // 5. 写回 extra 并持久化
        return events.map { event ->
            val signals = enriched[event.id] ?: return@map event
            val updatedExtra = event.extra.toMutableMap().apply {
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

    /** 判断事件是否需要预解析：PUBLISH_TWEET / TWEET_COMMENT 且有 targetId 且未解析过。 */
    private fun needsParsing(event: UserActionEvent): Boolean {
        if (event.type != UserActionType.PUBLISH_TWEET &&
            event.type != UserActionType.TWEET_COMMENT
        ) return false
        if (event.targetId.isNullOrBlank()) return false
        // 已解析过则跳过（缓存命中）
        return ProfileMappers.readExtraString(event.extra, KEY_TEXT_TOPIC) == null
    }

    /**
     * 按 targetId 回查事件关联的原文。
     *
     * - PUBLISH_TWEET：targetId 为推文 ID，从 tweets 表取 text 字段。
     * - TWEET_COMMENT：targetId 为被评论推文 ID，从 comments 表取 user-self
     *   作者的评论，按时间最近原则匹配。
     */
    private suspend fun retrieveText(event: UserActionEvent): String? {
        val targetId = event.targetId ?: return null
        return when (event.type) {
            UserActionType.PUBLISH_TWEET -> {
                tweetRepository.getById(targetId)?.text
            }
            UserActionType.TWEET_COMMENT -> {
                val comments = commentRepository.getByTweetAndAuthor(targetId, userSelfId)
                comments.minByOrNull { abs(it.createdAt - event.occurredAt) }?.content
            }
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    /** 批量调用 LLM 解析文本，返回 eventId → 信号的映射。 */
    private suspend fun batchParse(
        batch: List<TextBatchItem>,
        provider: LlmProvider,
    ): Map<String, TextSignals> {
        val messages = buildPrompt(batch)
        val raw = llmRegistry.getClient(provider).chatSync(
            messages = messages,
            config = ChatConfig(temperature = 0.2f, maxTokens = 768, jsonMode = true),
        )
        return parseResponse(raw, batch)
    }

    /** 构造 LLM 提示：system 指令 + user 输入（JSON 数组）。 */
    private fun buildPrompt(batch: List<TextBatchItem>): List<ChatMessage> {
        val system = ChatMessage(
            role = ChatMessage.Role.SYSTEM,
            content = SYSTEM_PROMPT,
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
        val user = ChatMessage(role = ChatMessage.Role.USER, content = userContent)
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
    private suspend fun persistExtra(eventId: String, extra: Map<String, kotlinx.serialization.json.JsonElement>) {
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
        internal const val KEY_TEXT_TOPIC = "textTopic"
        internal const val KEY_TEXT_TOPICS = "textTopics"
        internal const val KEY_TEXT_SENTIMENT = "textSentiment"
        internal const val KEY_TEXT_INTENT = "textIntent"
        private const val MAX_BATCH_SIZE = 20

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
