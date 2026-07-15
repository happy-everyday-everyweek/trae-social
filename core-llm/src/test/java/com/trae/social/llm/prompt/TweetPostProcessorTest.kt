package com.trae.social.llm.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * TweetPostProcessor 单元测试。
 *
 * 重点覆盖：
 * - applyTypos 在 typoRate=1.0 时所有可替换字符必定被替换。
 * - appendEmojis 末尾追加 1-2 个候选字符串。
 * - truncate 超长截断与省略号。
 */
class TweetPostProcessorTest {

    private val processor = TweetPostProcessor()

    @Test
    fun `applyTypos typoRate 为 0 时不修改原文`() {
        val text = "我在家做饭，得到的快乐很简单"
        val result = processor.applyTypos(text, 0.0, Random(42))
        assertEquals(text, result)
    }

    @Test
    fun `applyTypos typoRate 为 1 时所有可替换字符都被替换`() {
        // 含全部可替换字符：的、得、地、在、再、做、作
        val text = "我在再做作的得到地里"
        val result = processor.applyTypos(text, 1.0, Random(1))
        // 原文中的可替换字符不应再出现
        val originalReplaceable = setOf('的', '得', '地', '在', '再', '做', '作')
        for (ch in text) {
            if (ch in originalReplaceable) {
                // 该位置结果字符应与原字符不同
                val idx = text.indexOf(ch)
                assertTrue(
                    "位置 $idx 的字符 $ch 应被替换，实际为 ${result[idx]}",
                    result[idx] != ch
                )
            }
        }
    }

    @Test
    fun `applyTypos typoRate 为 1 时无可替换字符则原文不变`() {
        val text = "今天天气真好啊"
        val result = processor.applyTypos(text, 1.0, Random(7))
        assertEquals(text, result)
    }

    @Test
    fun `applyTypos 空字符串原样返回`() {
        assertEquals("", processor.applyTypos("", 1.0, Random(0)))
    }

    @Test
    fun `applyTypos 替换后文本长度不变`() {
        val text = "我在家做饭的得到"
        val result = processor.applyTypos(text, 1.0, Random(123))
        assertEquals(text.length, result.length)
    }

    @Test
    fun `appendEmojis 候选为空时原样返回`() {
        val text = "你好世界"
        assertEquals(text, processor.appendEmojis(text, emptyList(), Random(0)))
    }

    @Test
    fun `appendEmojis 末尾追加 1 到 2 个候选字符串`() {
        val text = "你好"
        // 使用占位字符串代表 emoji，避免在源码中出现字面 emoji。
        val emojis = listOf("[e1]", "[e2]", "[e3]")
        // 多次抽样确保覆盖 1 个与 2 个两种情况，且结果始终以原文开头。
        for (seed in 0 until 50) {
            val result = processor.appendEmojis(text, emojis, Random(seed.toLong()))
            assertTrue("结果应以原文开头: $result", result.startsWith(text))
            val suffix = result.removePrefix(text)
            assertTrue("追加部分不应为空: $result", suffix.isNotEmpty())
            // 追加部分仅由候选字符串组成
            for (ch in suffix) {
                assertTrue("追加了非候选字符: $ch", ch == '[' || ch == ']' || ch == 'e' || ch in '1'..'3')
            }
        }
    }

    @Test
    fun `truncate 文本未超长时原样返回`() {
        val text = "短文本"
        assertEquals(text, processor.truncate(text, 280))
    }

    @Test
    fun `truncate 文本超长时截断并追加省略号且总长不超过上限`() {
        val text = "a".repeat(300)
        val result = processor.truncate(text, 280)
        assertTrue("结果长度应不超过 280: ${result.length}", result.length <= 280)
        assertTrue("应以省略号结尾: $result", result.endsWith("…"))
    }

    @Test
    fun `truncate 上限为 1 时仅保留省略号`() {
        val text = "abcde"
        val result = processor.truncate(text, 1)
        assertEquals("…", result)
    }

    @Test
    fun `truncate 截断位置落在代理对中间时回退避免孤立代理项`() {
        // 用码点构造 emoji（高位+低位代理对），避免源码中出现字面 emoji。
        val emoji = String(intArrayOf(0x1F600), 0, 1)
        // "ab" + emoji + 后续长文本，使 text.length > max。
        // max=4 -> cut=3，恰好落在 emoji 高位代理之后、低位代理之前。
        val text = "ab" + emoji + "cdefghijklmnop"
        val result = processor.truncate(text, 4)
        // cut 应回退至 2，结果为 "ab…"，不含孤立高位代理。
        assertEquals("ab…", result)
    }

    @Test
    fun `truncate 多个 emoji 连续时不在代理对中间截断`() {
        val emoji = String(intArrayOf(0x1F600), 0, 1)
        // 两个连续 emoji，max=3 -> cut=2 落在第一个 emoji 高位代理之后。
        val text = emoji + emoji + "xyz"
        val result = processor.truncate(text, 3)
        // 结果不得以孤立高位代理结尾；总长不超过 max。
        assertTrue("结果长度应不超过 3: ${result.length}", result.length <= 3)
        assertTrue("应以省略号结尾: $result", result.endsWith("…"))
        // 截断点前不应是高位代理
        val beforeEllipsis = result.dropLast(1)
        if (beforeEllipsis.isNotEmpty()) {
            assertTrue(
                "截断点不应落在高位代理之后: $result",
                !beforeEllipsis.last().isHighSurrogate()
            )
        }
    }
}
