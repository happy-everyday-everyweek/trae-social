package com.trae.social.llm.prompt

import com.trae.social.llm.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TweetPromptBuilder 单元测试。
 *
 * 覆盖：
 * - 构建的消息含人设全部固定字段（RISK-2）。
 * - parseTweetResult 成功解析 JSON 与失败降级（RISK-13）。
 */
class TweetPromptBuilderTest {

    private val builder = TweetPromptBuilder()

    // #292c：samplePersona 已抽至 TestPersonas.kt，与本文件同包可直接调用。

    @Test
    fun `build 返回 system 与 user 两条消息`() {
        val messages = builder.build(
            persona = samplePersona(),
            timeSlotDescription = "工作日上午 09:00-12:00",
            recentTweets = listOf("今天又加班了", "新需求真多"),
        )
        assertEquals(2, messages.size)
        assertEquals(ChatMessage.Role.SYSTEM, messages[0].role)
        assertEquals(ChatMessage.Role.USER, messages[1].role)
    }

    @Test
    fun `system 消息含人设全部固定字段`() {
        val p = samplePersona()
        val messages = builder.build(p, "工作日上午", emptyList())
        val system = messages[0].content

        assertTrue("应含显示名", system.contains(p.displayName))
        assertTrue("应含职业", system.contains(p.profession))
        assertTrue("应含年龄段", system.contains(p.ageRange))
        assertTrue("应含文化背景", system.contains(p.culturalBackground))
        assertTrue("应含世界观", system.contains(p.worldview))
        assertTrue("应含价值观", system.contains(p.values))
        assertTrue("应含语言风格", system.contains(p.languageStyle))
        assertTrue("应含口癖", system.contains(p.catchphrase))
        assertTrue("应含错别字率", system.contains(p.typoRate.toString()))
        assertTrue("应含最近情绪", system.contains(p.recentMood))
        assertTrue("应含第一人称指令", system.contains("第一人称"))
        assertTrue("应含合规自检指令", system.contains("暴力"))
    }

    @Test
    fun `user 消息含时段与最近推文`() {
        val messages = builder.build(
            persona = samplePersona(),
            timeSlotDescription = "周末下午 14:00-18:00",
            recentTweets = listOf("推文一", "推文二", "推文三"),
        )
        val user = messages[1].content
        assertTrue(user.contains("周末下午 14:00-18:00"))
        assertTrue(user.contains("推文一"))
        assertTrue(user.contains("推文二"))
        assertTrue(user.contains("推文三"))
        assertTrue(user.contains("280"))
        assertTrue(user.contains("interactionTendency"))
    }

    @Test
    fun `recentTweets 为空时 user 消息含占位说明`() {
        val messages = builder.build(
            persona = samplePersona(),
            timeSlotDescription = "上午",
            recentTweets = emptyList(),
        )
        assertTrue(messages[1].content.contains("暂无历史推文"))
    }

    @Test
    fun `parseTweetResult 成功解析标准 JSON`() {
        val raw = "好的，以下是结果：\n$validTweetJson"
        val result = TweetPromptBuilder.parseTweetResult(raw)
        assertNotNull(result)
        assertEquals("今天天气真不错", result!!.text)
        assertTrue(result.withImage)
        assertEquals(TweetPromptBuilder.ImageTheme.LANDSCAPE, result.imageTheme)
        assertEquals(0.8, result.interactionTendency, 0.0001)
    }

    @Test
    fun `parseTweetResult 解析 markdown 代码块包裹的 JSON`() {
        val raw = """
            ```json
            {"text": "代码即诗", "withImage": false, "imageTheme": "none", "interactionTendency": 0.2}
            ```
        """.trimIndent()
        val result = TweetPromptBuilder.parseTweetResult(raw)
        assertNotNull(result)
        assertEquals("代码即诗", result!!.text)
        assertEquals(false, result.withImage)
        assertEquals(TweetPromptBuilder.ImageTheme.NONE, result.imageTheme)
        assertEquals(0.2, result.interactionTendency, 0.0001)
    }

    @Test
    fun `parseTweetResult 字段缺失时降级为默认值`() {
        // 缺少 withImage / imageTheme / interactionTendency，应使用默认值而非返回 null。
        val raw = """{"text": "只有文本"}"""
        val result = TweetPromptBuilder.parseTweetResult(raw)
        assertNotNull(result)
        assertEquals("只有文本", result!!.text)
        assertEquals(false, result.withImage)
        assertEquals(TweetPromptBuilder.ImageTheme.NONE, result.imageTheme)
        assertEquals(0.5, result.interactionTendency, 0.0001)
    }

    @Test
    fun `parseTweetResult interactionTendency 超界时被夹紧到 0 到 1`() {
        val raw = """{"text": "x", "withImage": false, "imageTheme": "none", "interactionTendency": 1.5}"""
        val result = TweetPromptBuilder.parseTweetResult(raw)
        assertNotNull(result)
        assertEquals(1.0, result!!.interactionTendency, 0.0001)

        val raw2 = """{"text": "x", "withImage": false, "imageTheme": "none", "interactionTendency": -0.3}"""
        val result2 = TweetPromptBuilder.parseTweetResult(raw2)
        assertNotNull(result2)
        assertEquals(0.0, result2!!.interactionTendency, 0.0001)
    }

    @Test
    fun `parseTweetResult 缺少 text 字段时返回 null`() {
        val raw = """{"withImage": true, "imageTheme": "food"}"""
        val result = TweetPromptBuilder.parseTweetResult(raw)
        assertNull(result)
    }

    @Test
    fun `parseTweetResult 纯文本无 JSON 时返回 null`() {
        val result = TweetPromptBuilder.parseTweetResult(malformedJson)
        assertNull(result)
    }

    @Test
    fun `parseTweetResult 非法 imageTheme 降级为 NONE`() {
        val raw = """{"text": "x", "withImage": true, "imageTheme": "unknown_theme", "interactionTendency": 0.5}"""
        val result = TweetPromptBuilder.parseTweetResult(raw)
        assertNotNull(result)
        assertEquals(TweetPromptBuilder.ImageTheme.NONE, result!!.imageTheme)
    }
}
