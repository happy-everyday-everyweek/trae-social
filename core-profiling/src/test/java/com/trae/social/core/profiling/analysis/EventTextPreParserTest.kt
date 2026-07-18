package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.entity.CommentEntity
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.entity.LlmEndpointEntity
import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.data.repository.CommentRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.llm.RulesetEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * EventTextPreParser 单元测试（#146 算法优化）。
 *
 * 覆盖：
 * - PUBLISH_TWEET 文本回查 + LLM 预解析写回 extra
 * - TWEET_COMMENT 文本回查（时间最近匹配）+ LLM 预解析
 * - 已解析事件跳过（缓存命中）
 * - LLM provider 未配置时优雅降级
 * - 无文本事件不受影响
 * - extra 持久化调用
 */
class EventTextPreParserTest {

    private val rulesetEngine: RulesetEngine = mockk()
    private val tweetRepository: TweetRepository = mockk()
    private val commentRepository: CommentRepository = mockk()
    private val userActionDao: UserActionDao = mockk(relaxed = true)
    private val configRepository: ConfigRepository = mockk()

    private val parser = EventTextPreParser(
        rulesetEngine = rulesetEngine,
        tweetRepository = tweetRepository,
        commentRepository = commentRepository,
        userActionDao = userActionDao,
        configRepository = configRepository,
    )

    private val now = System.currentTimeMillis()

    @Test
    fun `PUBLISH_TWEET 事件回查推文文本并写入 textTopic`() = runTest {
        val event = mkEvent(
            id = "e1",
            type = UserActionType.PUBLISH_TWEET,
            targetId = "tweet-1",
            occurredAt = now,
        )
        val tweet = mkTweet("tweet-1", "今天去拍了好多胶片照片，太开心了")
        val llmResponse = """[{"index":0,"textTopic":"摄影","textTopics":["胶片"],"textSentiment":"positive","textIntent":"share"}]"""

        coEvery { tweetRepository.getById("tweet-1") } returns tweet
        coEvery { configRepository.listEndpoints() } returns listOf(mkEndpoint())
        coEvery { rulesetEngine.chatSync(any(), any()) } returns llmResponse

        val result = parser.enrichWithTextSignals(listOf(event))

        assertEquals(1, result.size)
        val enriched = result[0]
        assertEquals("摄影", enriched.extra["textTopic"]?.let { (it as JsonPrimitive).content })
        assertEquals("positive", enriched.extra["textSentiment"]?.let { (it as JsonPrimitive).content })
        assertEquals("share", enriched.extra["textIntent"]?.let { (it as JsonPrimitive).content })
        assertEquals(true, enriched.extra["textParsed"]?.let { (it as JsonPrimitive).content.toBooleanStrict() })
        coVerify { userActionDao.updateExtra("e1", any()) }
    }

    @Test
    fun `TWEET_COMMENT 事件按时间最近匹配评论并写入 textTopic`() = runTest {
        val eventTs = now
        val event = mkEvent(
            id = "e2",
            type = UserActionType.TWEET_COMMENT,
            targetId = "tweet-2",
            occurredAt = eventTs,
        )
        // 两条评论，选择时间最近的
        val comments = listOf(
            mkComment("c1", "tweet-2", "评论 A", eventTs - 60_000),
            mkComment("c2", "tweet-2", "这道菜真好吃，推荐给大家", eventTs + 1_000),
        )
        val llmResponse = """[{"index":0,"textTopic":"美食","textTopics":[],"textSentiment":"positive","textIntent":"recommendation"}]"""

        coEvery { commentRepository.getByTweetAndAuthor("tweet-2", "user-self") } returns comments
        coEvery { configRepository.listEndpoints() } returns listOf(mkEndpoint())
        coEvery { rulesetEngine.chatSync(any(), any()) } returns llmResponse

        val result = parser.enrichWithTextSignals(listOf(event))

        assertEquals(1, result.size)
        val enriched = result[0]
        assertEquals("美食", enriched.extra["textTopic"]?.let { (it as JsonPrimitive).content })
        assertEquals("recommendation", enriched.extra["textIntent"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `已解析事件（extra 含 textParsed=true）跳过 LLM 调用`() = runTest {
        val event = mkEvent(
            id = "e3",
            type = UserActionType.PUBLISH_TWEET,
            targetId = "tweet-3",
            occurredAt = now,
            extra = mapOf(
                "textParsed" to JsonPrimitive(true),
                "textTopic" to JsonPrimitive("旅行"),
            ),
        )
        val result = parser.enrichWithTextSignals(listOf(event))
        assertEquals(1, result.size)
        assertEquals("旅行", result[0].extra["textTopic"]?.let { (it as JsonPrimitive).content })
        coVerify(exactly = 0) { rulesetEngine.chatSync(any(), any()) }
        coVerify(exactly = 0) { tweetRepository.getById(any()) }
    }

    @Test
    fun `textParsed 标记写入 extra 且 LLM 返回 topic=null 时不重复解析`() = runTest {
        // 第一轮：LLM 返回 topic=null 但 sentiment=positive
        val event = mkEvent(
            id = "e3b",
            type = UserActionType.PUBLISH_TWEET,
            targetId = "tweet-3b",
            occurredAt = now,
        )
        coEvery { tweetRepository.getById("tweet-3b") } returns mkTweet("tweet-3b", "some text")
        coEvery { configRepository.listEndpoints() } returns listOf(mkEndpoint())
        coEvery { rulesetEngine.chatSync(any(), any()) } returns
            """[{"index":0,"textTopic":null,"textTopics":[],"textSentiment":"positive","textIntent":"share"}]"""

        val result = parser.enrichWithTextSignals(listOf(event))
        assertEquals(1, result.size)
        // textParsed 标记应写入
        assertEquals(true, result[0].extra["textParsed"]?.let { (it as JsonPrimitive).content.toBooleanStrict() })
        // topic 为 null，不应写入 textTopic
        assertNull(result[0].extra["textTopic"])
        // sentiment 应写入
        assertEquals("positive", result[0].extra["textSentiment"]?.let { (it as JsonPrimitive).content })

        // 第二轮：同一事件不应再调用 LLM（textParsed=true 缓存命中）
        val result2 = parser.enrichWithTextSignals(listOf(result[0]))
        assertEquals(1, result2.size)
        coVerify(exactly = 1) { rulesetEngine.chatSync(any(), any()) }
    }

    @Test
    fun `LLM 端点未配置时优雅降级返回原始事件`() = runTest {
        val event = mkEvent(
            id = "e4",
            type = UserActionType.PUBLISH_TWEET,
            targetId = "tweet-4",
            occurredAt = now,
        )
        coEvery { tweetRepository.getById("tweet-4") } returns mkTweet("tweet-4", "some text")
        coEvery { configRepository.listEndpoints() } returns emptyList()

        val result = parser.enrichWithTextSignals(listOf(event))
        assertEquals(1, result.size)
        assertNull(result[0].extra["textTopic"])
        coVerify(exactly = 0) { rulesetEngine.chatSync(any(), any()) }
    }

    @Test
    fun `无文本事件（如 TWEET_VIEW）不受影响`() = runTest {
        val viewEvent = mkEvent(
            id = "e5",
            type = UserActionType.TWEET_VIEW,
            targetId = "tweet-5",
            occurredAt = now,
        )
        val result = parser.enrichWithTextSignals(listOf(viewEvent))
        assertEquals(1, result.size)
        assertNull(result[0].extra["textTopic"])
        coVerify(exactly = 0) { rulesetEngine.chatSync(any(), any()) }
    }

    @Test
    fun `推文已被删除（getById 返回 null）时跳过该事件`() = runTest {
        val event = mkEvent(
            id = "e6",
            type = UserActionType.PUBLISH_TWEET,
            targetId = "deleted-tweet",
            occurredAt = now,
        )
        coEvery { tweetRepository.getById("deleted-tweet") } returns null

        val result = parser.enrichWithTextSignals(listOf(event))
        assertEquals(1, result.size)
        assertNull(result[0].extra["textTopic"])
        coVerify(exactly = 0) { rulesetEngine.chatSync(any(), any()) }
    }

    @Test
    fun `LLM 返回非法 JSON 时优雅降级`() = runTest {
        val event = mkEvent(
            id = "e7",
            type = UserActionType.PUBLISH_TWEET,
            targetId = "tweet-7",
            occurredAt = now,
        )
        coEvery { tweetRepository.getById("tweet-7") } returns mkTweet("tweet-7", "text")
        coEvery { configRepository.listEndpoints() } returns listOf(mkEndpoint())
        coEvery { rulesetEngine.chatSync(any(), any()) } returns "not a json"

        val result = parser.enrichWithTextSignals(listOf(event))
        assertEquals(1, result.size)
        assertNull(result[0].extra["textTopic"])
    }

    @Test
    fun `混合事件列表只解析携带文本的事件`() = runTest {
        val viewEvent = mkEvent("e8a", UserActionType.TWEET_VIEW, "tweet-8", now)
        val publishEvent = mkEvent("e8b", UserActionType.PUBLISH_TWEET, "tweet-8b", now)
        val commentEvent = mkEvent("e8c", UserActionType.TWEET_COMMENT, "tweet-8c", now)

        coEvery { tweetRepository.getById("tweet-8b") } returns mkTweet("tweet-8b", "分享我的摄影作品")
        coEvery { commentRepository.getByTweetAndAuthor("tweet-8c", "user-self") } returns
            listOf(mkComment("c8", "tweet-8c", "拍得真好", now))
        coEvery { configRepository.listEndpoints() } returns listOf(mkEndpoint())
        coEvery { rulesetEngine.chatSync(any(), any()) } returns
            """[{"index":0,"textTopic":"摄影","textTopics":[],"textSentiment":"positive","textIntent":"share"},
               {"index":1,"textTopic":"摄影","textTopics":[],"textSentiment":"positive","textIntent":"opinion"}]"""

        val result = parser.enrichWithTextSignals(listOf(viewEvent, publishEvent, commentEvent))
        assertEquals(3, result.size)
        assertNull(result[0].extra["textTopic"])
        assertNotNull(result[1].extra["textTopic"])
        assertNotNull(result[2].extra["textTopic"])
    }

    /**
     * #150 review Q1：LLM 漏返回某 index 时，该事件仍应标记 textParsed=true，
     * 避免下个分析窗口重复送 LLM 消耗配额。
     */
    @Test
    fun `LLM 漏返回某 index 时仍标记 textParsed 避免重复解析`() = runTest {
        val event1 = mkEvent("e9a", UserActionType.PUBLISH_TWEET, "tweet-9a", now)
        val event2 = mkEvent("e9b", UserActionType.PUBLISH_TWEET, "tweet-9b", now)
        // LLM 只返回了 index=0，漏掉了 index=1
        coEvery { tweetRepository.getById("tweet-9a") } returns mkTweet("tweet-9a", "摄影分享")
        coEvery { tweetRepository.getById("tweet-9b") } returns mkTweet("tweet-9b", "美食探店")
        coEvery { configRepository.listEndpoints() } returns listOf(mkEndpoint())
        coEvery { rulesetEngine.chatSync(any(), any()) } returns
            """[{"index":0,"textTopic":"摄影","textTopics":[],"textSentiment":"positive","textIntent":"share"}]"""

        val result = parser.enrichWithTextSignals(listOf(event1, event2))
        assertEquals(2, result.size)
        // event1（index=0）正常解析
        assertEquals("摄影", result[0].extra["textTopic"]?.let { (it as JsonPrimitive).content })
        assertEquals(true, result[0].extra["textParsed"]?.let { (it as JsonPrimitive).content.toBooleanStrict() })
        // event2（index=1 被 LLM 漏掉）仍应标记 textParsed=true，但不写 textTopic
        assertNull(result[1].extra["textTopic"])
        assertEquals(true, result[1].extra["textParsed"]?.let { (it as JsonPrimitive).content.toBooleanStrict() })

        // 第二轮：两个事件都应跳过 LLM（textParsed=true 缓存命中）
        parser.enrichWithTextSignals(result)
        coVerify(exactly = 1) { rulesetEngine.chatSync(any(), any()) }
    }

    /**
     * #150 review Q3：TWEET_COMMENT 事件携带 commentId 时，按 id 精确回查评论原文，
     * 不再按时间最近原则匹配（避免多条评论错配）。
     */
    @Test
    fun `TWEET_COMMENT 携带 commentId 时按 id 精确回查评论`() = runTest {
        val commentId = "comment-precise-id"
        val event = mkEvent(
            id = "e10",
            type = UserActionType.TWEET_COMMENT,
            targetId = "tweet-10",
            occurredAt = now,
            extra = mapOf("commentId" to JsonPrimitive(commentId)),
        )
        coEvery { commentRepository.getById(commentId) } returns
            mkComment(commentId, "tweet-10", "按 id 精确匹配的评论内容", now)
        coEvery { configRepository.listEndpoints() } returns listOf(mkEndpoint())
        coEvery { rulesetEngine.chatSync(any(), any()) } returns
            """[{"index":0,"textTopic":"科技","textTopics":[],"textSentiment":"neutral","textIntent":"opinion"}]"""

        val result = parser.enrichWithTextSignals(listOf(event))
        assertEquals(1, result.size)
        assertEquals("科技", result[0].extra["textTopic"]?.let { (it as JsonPrimitive).content })
        // 应按 id 精确查询，不应调用 getByTweetAndAuthor
        coVerify(exactly = 0) { commentRepository.getByTweetAndAuthor(any(), any()) }
    }

    // ---- helpers ----

    private fun mkEvent(
        id: String,
        type: UserActionType,
        targetId: String?,
        occurredAt: Long,
        extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ) = UserActionEvent(
        id = id,
        type = type,
        screen = "test",
        targetId = targetId,
        targetKind = "tweet",
        extra = extra,
        occurredAt = occurredAt,
        session = "session-test",
    )

    private fun mkTweet(id: String, text: String) = TweetEntity(
        id = id,
        authorId = "user-self",
        text = text,
        mediaPath = null,
        mediaTheme = null,
        createdAt = now,
        likeCount = 0,
        commentCount = 0,
        retweetCount = 0,
        isAiGenerated = false,
        deduplicationKey = "key-$id",
    )

    private fun mkComment(id: String, tweetId: String, content: String, createdAt: Long) = CommentEntity(
        id = id,
        tweetId = tweetId,
        authorId = "user-self",
        content = content,
        createdAt = createdAt,
    )

    private fun mkEndpoint(): LlmEndpointEntity = LlmEndpointEntity(
        id = "ep-test",
        displayName = "Test Endpoint",
        protocol = "openai_compatible",
        baseUrl = "https://api.test.com",
        model = "test-model",
        capabilities = "",
        orderIndex = 0,
        createdAt = now,
        updatedAt = now,
    )
}
