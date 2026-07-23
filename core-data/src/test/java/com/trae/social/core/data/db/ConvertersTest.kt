package com.trae.social.core.data.db

import com.trae.social.core.data.entity.InteractionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Converters] 单元测试（#280）。
 *
 * 覆盖 Room TypeConverters 的 JSON 序列化 / 反序列化与枚举映射，
 * 重点验证损坏 JSON 的降级策略（#176）。
 */
class ConvertersTest {

    private val converters = Converters()

    // ============ String List ============

    @Test
    fun `stringListToJson 正常序列化`() {
        val json = converters.stringListToJson(listOf("a", "b", "c"))
        assertEquals("[\"a\",\"b\",\"c\"]", json)
    }

    @Test
    fun `stringListToJson null 返回空数组`() {
        assertEquals("[]", converters.stringListToJson(null))
    }

    @Test
    fun `stringListToJson 空列表返回空数组`() {
        assertEquals("[]", converters.stringListToJson(emptyList()))
    }

    @Test
    fun `jsonToStringList 正常反序列化`() {
        val list = converters.jsonToStringList("[\"x\",\"y\"]")
        assertEquals(listOf("x", "y"), list)
    }

    @Test
    fun `jsonToStringList null 返回空列表`() {
        assertEquals(emptyList<String>(), converters.jsonToStringList(null))
    }

    @Test
    fun `jsonToStringList 空串返回空列表`() {
        assertEquals(emptyList<String>(), converters.jsonToStringList(""))
    }

    @Test
    fun `jsonToStringList 损坏 JSON 降级为空列表`() {
        // #176：损坏 JSON 不抛异常，降级为空列表避免查询崩溃
        assertEquals(emptyList<String>(), converters.jsonToStringList("{invalid"))
    }

    // ============ Boolean List ============

    @Test
    fun `booleanListToJson 正常序列化`() {
        val json = converters.booleanListToJson(listOf(true, false, true))
        assertEquals("[true,false,true]", json)
    }

    @Test
    fun `booleanListToJson null 返回空数组`() {
        assertEquals("[]", converters.booleanListToJson(null))
    }

    @Test
    fun `jsonToBooleanList 正常反序列化`() {
        val list = converters.jsonToBooleanList("[true,false]")
        assertEquals(listOf(true, false), list)
    }

    @Test
    fun `jsonToBooleanList 损坏 JSON 降级为空列表`() {
        assertEquals(emptyList<Boolean>(), converters.jsonToBooleanList("not json"))
    }

    // ============ InteractionType ============

    @Test
    fun `interactionTypeToString 正常映射`() {
        assertEquals("LIKE", converters.interactionTypeToString(InteractionType.LIKE))
        assertEquals("COMMENT", converters.interactionTypeToString(InteractionType.COMMENT))
    }

    @Test
    fun `interactionTypeToString null 返回 null`() {
        assertNull(converters.interactionTypeToString(null))
    }

    @Test
    fun `stringToInteractionType 正常映射`() {
        assertEquals(InteractionType.LIKE, converters.stringToInteractionType("LIKE"))
        assertEquals(InteractionType.COMMENT, converters.stringToInteractionType("COMMENT"))
    }

    @Test
    fun `stringToInteractionType null 降级为 LIKE`() {
        // 脏数据降级为 LIKE，避免 Intrinsics.checkNotNull NPE
        assertEquals(InteractionType.LIKE, converters.stringToInteractionType(null))
    }

    @Test
    fun `stringToInteractionType 空串降级为 LIKE`() {
        assertEquals(InteractionType.LIKE, converters.stringToInteractionType(""))
    }

    @Test
    fun `stringToInteractionType 未知枚举名降级为 LIKE`() {
        assertEquals(InteractionType.LIKE, converters.stringToInteractionType("UNKNOWN_TYPE"))
    }
}
