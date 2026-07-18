package com.trae.social.llm.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CommentPromptBuilder 单元测试。
 *
 * 覆盖消息构建与评论结果数组宽松解析（RISK-13）。
 */
class CommentPromptBuilderTest {

    private val builder = CommentPromptBuilder()

    private fun samplePersona(name: String) = TweetPromptBuilder.PersonaInput(
        displayName = name,
        profession = "设计师",
        ageRange = "25-34",
        culturalBackground = "华东",
        worldview = "美即正义",
        values = "细节决定成败",
        languageStyle = "文艺",
        catchphrase = "绝了",
        emojiPreference = emptyList(),
        typoRate = 0.0,
        recentMood = "平静",
    )

    @Test
    fun `build 返回 system 与 user 两条消息`() {
        val messages = builder.build(
            tweet = CommentPromptBuilder.TweetInput("今天写完了一个需求", "李雷", "程序员"),
            commenters = listOf(samplePersona("韩梅梅"), samplePersona("张三")),
        )
        assertEquals(2, messages.size)
        assertEquals(com.trae.social.llm.ChatMessage.Role.SYSTEM, messages[0].role)
        assertEquals(com.trae.social.llm.ChatMessage.Role.USER, messages[1].role)
    }

    @Test
    fun `user 消息含被评推文与评论者人设`() {
        val messages = builder.build(
            tweet = CommentPromptBuilder.TweetInput("正文内容X", "作者Y", "程序员"),
            commenters = listOf(samplePersona("评论者A"), samplePersona("评论者B")),
        )
        val user = messages[1].content
        assertTrue(user.contains("正文内容X"))
        assertTrue(user.contains("作者Y"))
        assertTrue(user.contains("程序员"))
        assertTrue(user.contains("评论者A"))
        assertTrue(user.contains("评论者B"))
        assertTrue(user.contains("100"))
        assertTrue(user.contains("COMMENT/LIKE/RETWEET"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `build 评论者列表为空时抛出异常`() {
        builder.build(
            tweet = CommentPromptBuilder.TweetInput("x", "y", "z"),
            commenters = emptyList(),
        )
    }

    @Test
    fun `parseCommentResults 成功解析标准 JSON 数组`() {
        val raw = """
            [{"commenterIndex": 0, "text": "说得好", "type": "COMMENT"},
             {"commenterIndex": 1, "text": "", "type": "LIKE"},
             {"commenterIndex": 2, "text": "", "type": "RETWEET"}]
        """.trimIndent()
        val results = CommentPromptBuilder.parseCommentResults(raw)
        assertEquals(3, results.size)
        assertEquals(0, results[0].commenterIndex)
        assertEquals("说得好", results[0].text)
        assertEquals(CommentPromptBuilder.CommentType.COMMENT, results[0].type)
        assertEquals(1, results[1].commenterIndex)
        assertEquals("", results[1].text)
        assertEquals(CommentPromptBuilder.CommentType.LIKE, results[1].type)
        assertEquals(CommentPromptBuilder.CommentType.RETWEET, results[2].type)
    }

    @Test
    fun `parseCommentResults 解析带 markdown 代码块的数组`() {
        val raw = """
            ```json
            [{"commenterIndex": 0, "text": "赞", "type": "COMMENT"}]
            ```
        """.trimIndent()
        val results = CommentPromptBuilder.parseCommentResults(raw)
        assertEquals(1, results.size)
        assertEquals("赞", results[0].text)
    }

    @Test
    fun `parseCommentResults 单条字段异常时跳过该条不影响其余`() {
        val raw = """
            [{"commenterIndex": 0, "text": "正常", "type": "COMMENT"},
             {"text": "缺index"},
             {"commenterIndex": 2, "text": "也正常", "type": "LIKE"}]
        """.trimIndent()
        val results = CommentPromptBuilder.parseCommentResults(raw)
        assertEquals(2, results.size)
        assertEquals(0, results[0].commenterIndex)
        assertEquals(2, results[1].commenterIndex)
    }

    @Test
    fun `parseCommentResults 非法 type 降级为 COMMENT`() {
        val raw = """[{"commenterIndex": 0, "text": "x", "type": "UNKNOWN"}]"""
        val results = CommentPromptBuilder.parseCommentResults(raw)
        assertEquals(1, results.size)
        assertEquals(CommentPromptBuilder.CommentType.COMMENT, results[0].type)
    }

    @Test
    fun `parseCommentResults 纯文本无 JSON 返回空列表`() {
        val results = CommentPromptBuilder.parseCommentResults("这不是 JSON")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseCommentResults 缺少 type 字段时降级为 COMMENT`() {
        val raw = """[{"commenterIndex": 0, "text": "无类型"}]"""
        val results = CommentPromptBuilder.parseCommentResults(raw)
        assertEquals(1, results.size)
        assertEquals(CommentPromptBuilder.CommentType.COMMENT, results[0].type)
    }

    @Test
    fun `parseCommentResults 传入 commenterCount 时过滤越界 index`() {
        val raw = """
            [{"commenterIndex": 0, "text": "正常", "type": "COMMENT"},
             {"commenterIndex": 99, "text": "越界", "type": "COMMENT"},
             {"commenterIndex": 1, "text": "也正常", "type": "LIKE"}]
        """.trimIndent()
        // 评论者数=2，index 99 越界应在 builder 层被过滤。
        val results = CommentPromptBuilder.parseCommentResults(raw, commenterCount = 2)
        assertEquals(2, results.size)
        assertEquals(0, results[0].commenterIndex)
        assertEquals(1, results[1].commenterIndex)
    }

    @Test
    fun `parseCommentResults 不传 commenterCount 时向后兼容不过滤越界`() {
        val raw = """[{"commenterIndex": 99, "text": "x", "type": "COMMENT"}]"""
        val results = CommentPromptBuilder.parseCommentResults(raw)
        assertEquals(1, results.size)
        assertEquals(99, results[0].commenterIndex)
    }

    // ---- #146 A/E 场景 4 commentPersona：UserTasteHint 注入测试 ----

    @Test
    fun `build 不传 userTaste 时不包含用户口味提示段（control 路径）`() {
        val messages = builder.build(
            tweet = CommentPromptBuilder.TweetInput("正文", "作者", "程序员"),
            commenters = listOf(samplePersona("评论者A")),
        )
        val user = messages[1].content
        assertFalse(user.contains("【用户口味提示】"))
    }

    @Test
    fun `build 传 userTaste 时包含用户口味提示段（driven 路径）`() {
        val taste = CommentPromptBuilder.UserTasteHint(
            topThemes = listOf("编程", "音乐"),
            topInterestWeights = mapOf("编程" to 0.6, "音乐" to 0.3),
            narrative = null,
        )
        val messages = builder.build(
            tweet = CommentPromptBuilder.TweetInput("正文", "作者", "程序员"),
            commenters = listOf(samplePersona("评论者A")),
            userTaste = taste,
        )
        val user = messages[1].content
        assertTrue(user.contains("【用户口味提示】"))
        assertTrue(user.contains("编程"))
        assertTrue(user.contains("音乐"))
    }

    @Test
    fun `build userTaste 包含高权重主题排序展示`() {
        val taste = CommentPromptBuilder.UserTasteHint(
            topThemes = emptyList(),
            topInterestWeights = mapOf("编程" to 0.6, "音乐" to 0.3, "电影" to 0.1),
            narrative = null,
        )
        val messages = builder.build(
            tweet = CommentPromptBuilder.TweetInput("正文", "作者", "程序员"),
            commenters = listOf(samplePersona("评论者A")),
            userTaste = taste,
        )
        val user = messages[1].content
        assertTrue(user.contains("高权重主题"))
        // 编程权重最高应排在最前
        val progIdx = user.indexOf("编程")
        val musicIdx = user.indexOf("音乐")
        assertTrue(progIdx >= 0 && musicIdx >= 0 && progIdx < musicIdx)
    }

    @Test
    fun `build userTaste 包含 narrative 时展示用户背景`() {
        val taste = CommentPromptBuilder.UserTasteHint(
            topThemes = listOf("编程"),
            topInterestWeights = emptyMap(),
            narrative = "一个热爱技术的程序员",
        )
        val messages = builder.build(
            tweet = CommentPromptBuilder.TweetInput("正文", "作者", "程序员"),
            commenters = listOf(samplePersona("评论者A")),
            userTaste = taste,
        )
        val user = messages[1].content
        assertTrue(user.contains("用户背景"))
        assertTrue(user.contains("一个热爱技术的程序员"))
    }

    @Test
    fun `build userTaste 全空时不展示主题与权重行但保留提示段`() {
        val taste = CommentPromptBuilder.UserTasteHint(
            topThemes = emptyList(),
            topInterestWeights = emptyMap(),
            narrative = null,
        )
        val messages = builder.build(
            tweet = CommentPromptBuilder.TweetInput("正文", "作者", "程序员"),
            commenters = listOf(samplePersona("评论者A")),
            userTaste = taste,
        )
        val user = messages[1].content
        assertTrue(user.contains("【用户口味提示】"))
        assertFalse(user.contains("用户兴趣 Top 主题"))
        assertFalse(user.contains("高权重主题"))
        assertFalse(user.contains("用户背景"))
    }
}
