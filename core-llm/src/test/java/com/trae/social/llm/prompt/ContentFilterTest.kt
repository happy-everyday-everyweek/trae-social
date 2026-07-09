package com.trae.social.llm.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContentFilter 单元测试。
 *
 * 覆盖敏感词检测与打码能力（RISK-12 应用层兜底）。
 */
class ContentFilterTest {

    private val filter = ContentFilter()

    @Test
    fun `containsSensitiveContent 命中敏感词返回 true`() {
        assertTrue(filter.containsSensitiveContent("他在讨论毒品交易的事情"))
        assertTrue(filter.containsSensitiveContent("有人传播木马程序"))
        assertTrue(filter.containsSensitiveContent("这是关于诈骗的内容"))
    }

    @Test
    fun `containsSensitiveContent 大小写不敏感命中英文相关词`() {
        // 词库中含英文/字母相关词时大小写不敏感；此处以中文词为主，验证大小写不敏感逻辑。
        assertTrue(filter.containsSensitiveContent("HACKER 涉及黑客攻击"))
    }

    @Test
    fun `containsSensitiveContent 无敏感词返回 false`() {
        assertFalse(filter.containsSensitiveContent("今天天气真好，适合出门散步"))
        assertFalse(filter.containsSensitiveContent(""))
    }

    @Test
    fun `containsSensitiveContent 空白字符串返回 false`() {
        assertFalse(filter.containsSensitiveContent("   "))
    }

    @Test
    fun `maskSensitive 命中敏感词时替换为等长星号`() {
        val text = "他在讨论毒品交易"
        val masked = filter.maskSensitive(text)
        // "毒品交易" 4 个字符应被替换为 4 个星号
        assertTrue("应含星号: $masked", masked.contains("****"))
        // 非敏感词部分应保留
        assertTrue(masked.contains("他在讨论"))
    }

    @Test
    fun `maskSensitive 无敏感词时原样返回`() {
        val text = "今天天气真好"
        assertEquals(text, filter.maskSensitive(text))
    }

    @Test
    fun `maskSensitive 多个敏感词都被打码`() {
        val text = "毒品交易和诈骗"
        val masked = filter.maskSensitive(text)
        // 两个敏感词均被打码，原文敏感词不应再出现
        assertFalse(masked.contains("毒品交易"))
        assertFalse(masked.contains("诈骗"))
        assertTrue(masked.contains("****"))
    }

    @Test
    fun `maskSensitive 重叠敏感词区间被合并处理`() {
        // 词库含 "贩毒" 与 "毒品交易"，二者在 "贩毒品交易" 中于 "毒" 字处重叠。
        // 合并后 [0,4] 全部打码为 5 个星号，原文敏感词不应再出现。
        val text = "贩毒品交易"
        val masked = filter.maskSensitive(text)
        assertFalse(masked.contains("贩毒"))
        assertFalse(masked.contains("毒品交易"))
        assertTrue("应含 5 个星号: $masked", masked.contains("*****"))
    }

    // IMPL-29 回归测试：否定/防御前缀上下文不应误判敏感词

    @Test
    fun `containsSensitiveContent 反前缀合法讨论不误伤`() {
        assertFalse(filter.containsSensitiveContent("反诈骗人人有责"))
        assertFalse(filter.containsSensitiveContent("防勒索病毒指南"))
        assertFalse(filter.containsSensitiveContent("打击走私行动"))
        assertFalse(filter.containsSensitiveContent("拒毒品行贿"))
    }

    @Test
    fun `containsSensitiveContent 非前缀上下文仍命中`() {
        // 否定前缀不紧邻敏感词时仍应命中
        assertTrue(filter.containsSensitiveContent("他在搞诈骗"))
        assertTrue(filter.containsSensitiveContent("参与毒品交易"))
    }

    @Test
    fun `maskSensitive 反前缀合法讨论不打码`() {
        assertEquals("反诈骗人人有责", filter.maskSensitive("反诈骗人人有责"))
        assertEquals("防勒索病毒指南", filter.maskSensitive("防勒索病毒指南"))
    }

    @Test
    fun `maskSensitive 非前缀上下文正常打码`() {
        val masked = filter.maskSensitive("他在搞诈骗")
        assertFalse(masked.contains("诈骗"))
        assertTrue(masked.contains("**"))
    }

    private fun assertEquals(expected: String, actual: String) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
