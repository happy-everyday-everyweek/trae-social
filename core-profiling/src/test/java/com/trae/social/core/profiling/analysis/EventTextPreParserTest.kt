package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.entity.CommentEntity
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.data.repository.CommentRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.llm.LlmClient
import com.trae.social.llm.LlmProviderRegistry
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

    private val llmRegistry: LlmProviderRegistry = mockk()
    private val tweetRepository: TweetRepository = mockk()
    private val commentRepository: CommentRepository = mockk()
    private val userActionDao: UserActionDao = mockk(relaxed = true)
    private val configRepository: ConfigRepository = mockk()

    private val parser = EventTextPreParser(
        llmRegistry = llmRegistry,
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
        coEvery { configRepository.getDefaultProvider() } returns LlmProvider.OPENAI
        coEvery { llmRegistry.getClient(LlmProvider.OPENAI) } returns mkLlmClient(llmResponse)

        val result = parser.enrichWithTextSignals(listOf(event))

        assertEquals(1, result.size)
        val enriched = result[0]
        assertEquals("摄影", enriched.extra["textTopic"]?.let { (it as JsonPrimitive).content })
        assertEquals("positive", enriched.extra["textSentiment"]?.let { (it as JsonPrimitive).content })
        assertEquals("share", enriched.extra["textIntent"]?.let { (it as JsonPrimitive).content })
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
        coEvery { configRepository.getDefaultProvider() } returns LlmProvider.OPENAI
        coEvery { llmRegistry.getClient(LlmProvider.OPENAI) } returns mkLlmClient(llmResponse)

        val result = parser.enrichWithTextSignals(listOf(event))

        assertEquals(1, result.size)
        val enriched = result[0]
        assertEquals("美食", enriched.extra["textTopic"]?.let { (it as JsonPrimitive).content })
        assertEquals("recommendation", enriched.extra["textIntent"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `已解析事件（extra 含 textTopic）跳过 LLM 调用`() = runTest {
        val event = mkEvent(
            id = "e3",
            type = UserActionType.PUBLISH_TWEET,
            targetId = "tweet-3",
            occurredAt = now,
            extra = mapOf("textTopic" to JsonPrimitive("旅行")),
        )
        val result = parser.enrichWithTextSignals(listOf(event))
        assertEquals(1, result.size)
        assertEquals("旅行", result[0].extra["textTopic"]?.let { (it as JsonPrimitive).content })
        coVerify(exactly = 0) { llmRegistry.getClient(any()) }
        coVerify(exactly = 0) { tweetRepository.getById(any()) }
    }

    @Test
    fun `LLM provider 未配置时优雅降级返回原始事件`() = runTest {
        val event = mkEvent(
            id = "e4",
            type = UserActionType.PUBLISH_TWEET,
            targetId = "tweet-4",
            occurredAt = now,
        )
        coEvery { tweetRepository.getById("tweet-4") } returns mkTweet("tweet-4", "some text")
        coEvery { configRepository.getDefaultProvider() } returns null

        val result = parser.enrichWithTextSignals(listOf(event))
        assertEquals(1, result.size)
        assertNull(result[0].extra["textTopic"])
        coVerify(exactly = 0) { llmRegistry.getClient(any()) }
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
        coVerify(exactly = 0) { llmRegistry.getClient(any()) }
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
        coVerify(exactly = 0) { llmRegistry.getClient(any()) }
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
        coEvery { configRepository.getDefaultProvider() } returns LlmProvider.OPENAI
        coEvery { llmRegistry.getClient(LlmProvider.OPENAI) } returns mkLlmClient("not a json")

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
        coEvery { configRepository.getDefaultProvider() } returns LlmProvider.OPENAI
        coEvery { llmRegistry.getClient(LlmProvider.OPENAI) } returns mkLlmClient(
            """[{"index":0,"textTopic":"摄影","textTopics":[],"textSentiment":"positive","textIntent":"share"},
               {"index":1,"textTopic":"摄影","textTopics":[],"textSentiment":"positive","textIntent":"opinion"}]"""
        )

        val result = parser.enrichWithTextSignals(listOf(viewEvent, publishEvent, commentEvent))
        assertEquals(3, result.size)
        assertNull(result[0].extra["textTopic"])
        assertNotNull(result[1].extra["textTopic"])
        assertNotNull(result[2].extra["textTopic"])
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

    private fun mkLlmClient(response: String): LlmClient = mockk {
        coEvery { chatSync(any(), any()) } returns response
    }
}
