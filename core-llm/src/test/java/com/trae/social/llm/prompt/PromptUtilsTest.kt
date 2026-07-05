package com.trae.social.llm.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * PromptUtils 单元测试。
 *
 * 覆盖 JSON 提取与安全解析的各类边界（RISK-13）。
 */
class PromptUtilsTest {

    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `extractJson 提取纯 JSON 对象`() {
        val raw = """{"a": 1, "b": "x"}"""
        assertEquals(raw, PromptUtils.extractJson(raw))
    }

    @Test
    fun `extractJson 从带前后说明文字中提取对象`() {
        val raw = """结果是 {"a": 1} 完成"""
        assertEquals("""{"a": 1}""", PromptUtils.extractJson(raw))
    }

    @Test
    fun `extractJson 从 markdown 代码块中提取对象`() {
        val raw = """
            ```json
            {"a": 1}
            ```
        """.trimIndent()
        assertEquals("""{"a": 1}""", PromptUtils.extractJson(raw))
    }

    @Test
    fun `extractJson 无大括号时返回 null`() {
        assertNull(PromptUtils.extractJson("纯文本"))
    }

    @Test
    fun `extractJsonArray 提取纯 JSON 数组`() {
        val raw = """[{"a": 1}, {"b": 2}]"""
        assertEquals(raw, PromptUtils.extractJsonArray(raw))
    }

    @Test
    fun `extractJsonArray 从 markdown 代码块中提取数组`() {
        val raw = """
            ```json
            [{"a": 1}]
            ```
        """.trimIndent()
        assertEquals("""[{"a": 1}]""", PromptUtils.extractJsonArray(raw))
    }

    @Test
    fun `extractJsonArray 无数组时回退到对象提取`() {
        val raw = """结果 {"a": 1} 结束"""
        assertEquals("""{"a": 1}""", PromptUtils.extractJsonArray(raw))
    }

    @Test
    fun `safeParseJson 合法 JSON 返回对象`() {
        val obj = PromptUtils.safeParseJson("""{"a": 1}""", parser)
        assertNotNull(obj)
        assertEquals("1", obj!!["a"]?.jsonPrimitive?.content)
    }

    @Test
    fun `safeParseJson 非法 JSON 返回 null`() {
        assertNull(PromptUtils.safeParseJson("不是 JSON", parser))
    }
}
