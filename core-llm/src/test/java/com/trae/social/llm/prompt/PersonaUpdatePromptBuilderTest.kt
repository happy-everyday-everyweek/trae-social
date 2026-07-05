package com.trae.social.llm.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PersonaUpdatePromptBuilder 单元测试。
 *
 * 重点覆盖：
 * - cosineSimilarity 字符级 Jaccard 计算正确。
 * - shouldRollback 阈值判定。
 * - parsePersonaUpdate 成功与失败降级。
 */
class PersonaUpdatePromptBuilderTest {

    private val builder = PersonaUpdatePromptBuilder()

    @Test
    fun `build 返回 system 与 user 两条消息`() {
        val input = PersonaUpdatePromptBuilder.PersonaDynamicInput(
            lifeStory = "出生在江南小镇，大学读计算机",
            workInfo = "在某互联网公司做后端",
            mood = "稳定",
            relationshipNetwork = "同事若干",
        )
        val messages = builder.build(input, listOf("发布了一条推文", "收到一条评论"))
        assertEquals(2, messages.size)
        assertEquals(com.trae.social.llm.ChatMessage.Role.SYSTEM, messages[0].role)
        assertEquals(com.trae.social.llm.ChatMessage.Role.USER, messages[1].role)
    }

    @Test
    fun `user 消息含当前动态字段与事件`() {
        val input = PersonaUpdatePromptBuilder.PersonaDynamicInput(
            lifeStory = "人生经历X",
            workInfo = "工作信息Y",
            mood = "情绪Z",
            relationshipNetwork = "关系网W",
        )
        val messages = builder.build(input, listOf("事件A", "事件B"))
        val user = messages[1].content
        assertTrue(user.contains("人生经历X"))
        assertTrue(user.contains("工作信息Y"))
        assertTrue(user.contains("情绪Z"))
        assertTrue(user.contains("关系网W"))
        assertTrue(user.contains("事件A"))
        assertTrue(user.contains("事件B"))
    }

    @Test
    fun `system 消息含一致性约束`() {
        val messages = builder.build(
            PersonaUpdatePromptBuilder.PersonaDynamicInput("x", "y", "z", "w"),
            emptyList(),
        )
        val system = messages[0].content
        assertTrue(system.contains("人设演进引擎"))
        assertTrue(system.contains("不要突变"))
    }

    @Test
    fun `cosineSimilarity 相同字符串返回 1`() {
        val s = "今天天气真好"
        assertEquals(1.0, PersonaUpdatePromptBuilder.cosineSimilarity(s, s), 0.0001)
    }

    @Test
    fun `cosineSimilarity 完全不同字符返回 0`() {
        val a = "abc"
        val b = "xyz"
        assertEquals(0.0, PersonaUpdatePromptBuilder.cosineSimilarity(a, b), 0.0001)
    }

    @Test
    fun `cosineSimilarity 部分重叠按 Jaccard 计算`() {
        // 字符集合：{a,b,c} 与 {b,c,d}
        // 交集 {b,c} size=2，并集 {a,b,c,d} size=4，相似度 0.5
        val a = "abc"
        val b = "bcd"
        assertEquals(0.5, PersonaUpdatePromptBuilder.cosineSimilarity(a, b), 0.0001)
    }

    @Test
    fun `cosineSimilarity 两空串返回 1`() {
        assertEquals(1.0, PersonaUpdatePromptBuilder.cosineSimilarity("", ""), 0.0001)
    }

    @Test
    fun `cosineSimilarity 一空一非空返回 0`() {
        assertEquals(0.0, PersonaUpdatePromptBuilder.cosineSimilarity("abc", ""), 0.0001)
        assertEquals(0.0, PersonaUpdatePromptBuilder.cosineSimilarity("", "abc"), 0.0001)
    }

    @Test
    fun `shouldRollback 相似度低于阈值返回 true`() {
        // 完全不同的字符，相似度 0 < 0.3
        assertTrue(PersonaUpdatePromptBuilder.shouldRollback("abc", "xyz"))
    }

    @Test
    fun `shouldRollback 相似度高于阈值返回 false`() {
        // 相同字符串相似度 1.0 > 0.3
        assertFalse(PersonaUpdatePromptBuilder.shouldRollback("今天天气真好", "今天天气真好"))
    }

    @Test
    fun `shouldRollback 自定义阈值生效`() {
        // abc vs bcd 相似度 0.5
        // 阈值 0.3 -> false（不回退）；阈值 0.6 -> true（回退）
        assertFalse(PersonaUpdatePromptBuilder.shouldRollback("abc", "bcd", threshold = 0.3))
        assertTrue(PersonaUpdatePromptBuilder.shouldRollback("abc", "bcd", threshold = 0.6))
    }

    @Test
    fun `parsePersonaUpdate 成功解析标准 JSON`() {
        val raw = """{"lifeStory": "新经历", "workInfo": "新工作", "mood": "新情绪"}"""
        val result = PersonaUpdatePromptBuilder.parsePersonaUpdate(raw)
        assertNotNull(result)
        assertEquals("新经历", result!!.lifeStory)
        assertEquals("新工作", result.workInfo)
        assertEquals("新情绪", result.mood)
    }

    @Test
    fun `parsePersonaUpdate 解析带前后说明文字的 JSON`() {
        val raw = """
            好的，已更新人设：
            {"lifeStory": "经历A", "workInfo": "工作B", "mood": "情绪C"}
            以上为更新结果。
        """.trimIndent()
        val result = PersonaUpdatePromptBuilder.parsePersonaUpdate(raw)
        assertNotNull(result)
        assertEquals("经历A", result!!.lifeStory)
        assertEquals("工作B", result.workInfo)
        assertEquals("情绪C", result.mood)
    }

    @Test
    fun `parsePersonaUpdate 缺少字段返回 null`() {
        val raw = """{"lifeStory": "x", "workInfo": "y"}"""
        assertNull(PersonaUpdatePromptBuilder.parsePersonaUpdate(raw))
    }

    @Test
    fun `parsePersonaUpdate 无 JSON 返回 null`() {
        assertNull(PersonaUpdatePromptBuilder.parsePersonaUpdate("纯文本无 JSON"))
    }
}
