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
 * - jaccardSimilarity 字符级 Jaccard 计算正确。
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
        val user = messages[1].textContent()
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
        val system = messages[0].textContent()
        assertTrue(system.contains("人设演进引擎"))
        assertTrue(system.contains("不要突变"))
    }

    @Test
    fun `jaccardSimilarity 相同字符串返回 1`() {
        val s = "今天天气真好"
        assertEquals(1.0, PersonaUpdatePromptBuilder.jaccardSimilarity(s, s), 0.0001)
    }

    @Test
    fun `jaccardSimilarity 完全不同字符返回 0`() {
        val a = "abc"
        val b = "xyz"
        assertEquals(0.0, PersonaUpdatePromptBuilder.jaccardSimilarity(a, b), 0.0001)
    }

    @Test
    fun `jaccardSimilarity 部分重叠按 Jaccard 计算`() {
        // B4 修复：#85 改用 bigram 后，"abc"->{ab,bc}、"bcd"->{bc,cd}
        // 交集 {bc} size=1，并集 {ab,bc,cd} size=3，相似度 1/3 ≈ 0.3333
        val a = "abc"
        val b = "bcd"
        assertEquals(1.0 / 3.0, PersonaUpdatePromptBuilder.jaccardSimilarity(a, b), 0.001)
    }

    @Test
    fun `jaccardSimilarity 两空串返回 1`() {
        assertEquals(1.0, PersonaUpdatePromptBuilder.jaccardSimilarity("", ""), 0.0001)
    }

    @Test
    fun `jaccardSimilarity 一空一非空返回 0`() {
        assertEquals(0.0, PersonaUpdatePromptBuilder.jaccardSimilarity("abc", ""), 0.0001)
        assertEquals(0.0, PersonaUpdatePromptBuilder.jaccardSimilarity("", "abc"), 0.0001)
    }

    @Test
    fun `shouldRollback 相似度低于阈值返回 true`() {
        // 完全不同的字符，相似度 0 < 0.5
        assertTrue(PersonaUpdatePromptBuilder.shouldRollback("abc", "xyz"))
    }

    @Test
    fun `shouldRollback 相似度高于阈值返回 false`() {
        // 相同字符串相似度 1.0 > 0.5
        assertFalse(PersonaUpdatePromptBuilder.shouldRollback("今天天气真好", "今天天气真好"))
    }

    @Test
    fun `shouldRollback 自定义阈值生效`() {
        // B4 修复：bigram 相似度 1/3 ≈ 0.333
        // 阈值 0.2 -> false（不回退）；阈值 0.5 -> true（回退）
        assertFalse(PersonaUpdatePromptBuilder.shouldRollback("abc", "bcd", threshold = 0.2))
        assertTrue(PersonaUpdatePromptBuilder.shouldRollback("abc", "bcd", threshold = 0.5))
    }

    @Test
    fun `parsePersonaUpdate 成功解析标准 JSON`() {
        val raw = validPersonaUpdateJson
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
        assertNull(PersonaUpdatePromptBuilder.parsePersonaUpdate(malformedJson))
    }

    // ---- #146 A/E 场景 7 personaCoEvolve：userInterests 注入测试 ----

    @Test
    fun `build 不传 userInterests 时不包含用户兴趣画像段（control 路径）`() {
        val input = PersonaUpdatePromptBuilder.PersonaDynamicInput("x", "y", "z", "w")
        val messages = builder.build(input, listOf("事件A"))
        val user = messages[1].textContent()
        assertFalse(user.contains("【用户兴趣画像】"))
    }

    @Test
    fun `build 传空 userInterests 时不包含用户兴趣画像段`() {
        val input = PersonaUpdatePromptBuilder.PersonaDynamicInput("x", "y", "z", "w")
        val messages = builder.build(input, listOf("事件A"), userInterests = emptyList())
        val user = messages[1].textContent()
        assertFalse(user.contains("【用户兴趣画像】"))
    }

    @Test
    fun `build 传非空 userInterests 时包含用户兴趣画像段（driven 路径）`() {
        val input = PersonaUpdatePromptBuilder.PersonaDynamicInput("x", "y", "z", "w")
        val messages = builder.build(
            input,
            listOf("事件A"),
            userInterests = listOf("编程", "音乐"),
        )
        val user = messages[1].textContent()
        assertTrue(user.contains("【用户兴趣画像】"))
        assertTrue(user.contains("编程"))
        assertTrue(user.contains("音乐"))
        assertTrue(user.contains("用户近期关注主题"))
    }

    @Test
    fun `build userInterests 注入段包含共演化引导语`() {
        val input = PersonaUpdatePromptBuilder.PersonaDynamicInput("x", "y", "z", "w")
        val messages = builder.build(
            input,
            emptyList(),
            userInterests = listOf("科技"),
        )
        val user = messages[1].textContent()
        assertTrue(user.contains("人设一致性"))
        assertTrue(user.contains("共演化") || user.contains("靠拢") || user.contains("共鸣"))
    }
}
