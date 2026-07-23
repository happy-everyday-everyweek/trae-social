package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.model.UserActionType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BasicProfileAnalyzer 文本融合逻辑单元测试（#146 算法优化）。
 *
 * 验证 computeInterestVector 融合 imageTheme / textTopic / textTopics 后，
 * 兴趣向量同时包含图片主题与文本主题，且 textTopic 权重高于 imageTheme。
 */
class BasicProfileAnalyzerTextFusionTest {

    // #290：使用固定时间戳替代 System.currentTimeMillis()，避免时间相关断言不稳定。
    // analyze 内部以 (now - occurredAt) 计算衰减，occurredAt 同样取该值时 ageDays=0、衰减系数=1.0，
    // 断言只依赖相对权重，与具体时间戳数值无关。
    private val now = 1_700_000_000_000L

    @Test
    fun `仅 imageTheme 时兴趣向量与原逻辑一致`() {
        val events = listOf(
            mkEvent(UserActionType.TWEET_VIEW, "t1", now, mapOf("imageTheme" to JsonPrimitive("风景"))),
            mkEvent(UserActionType.TWEET_VIEW, "t2", now, mapOf("imageTheme" to JsonPrimitive("美食"))),
            mkEvent(UserActionType.TWEET_LIKE, "t3", now, mapOf("imageTheme" to JsonPrimitive("风景"))),
        )
        val snapshot = BasicProfileAnalyzer.analyze(events, previous = null, now = now)
        assertTrue(snapshot.interestVector.containsKey("风景"))
        assertTrue(snapshot.interestVector.containsKey("美食"))
        // like (weight=3) + view (weight=1) 使风景权重高于美食
        assertTrue(snapshot.interestVector["风景"]!! > snapshot.interestVector["美食"]!!)
    }

    @Test
    fun `textTopic 融合后出现在兴趣向量中`() {
        val events = listOf(
            mkEvent(UserActionType.PUBLISH_TWEET, "t1", now, mapOf(
                "textTopic" to JsonPrimitive("摄影"),
                "captionLen" to JsonPrimitive(50),
                "imageCount" to JsonPrimitive(2),
            )),
        )
        val snapshot = BasicProfileAnalyzer.analyze(events, previous = null, now = now)
        assertTrue("textTopic 应出现在兴趣向量中", snapshot.interestVector.containsKey("摄影"))
    }

    @Test
    fun `textTopic 权重高于 imageTheme（主动表达大于被动浏览）`() {
        // 同一事件类型下，imageTheme 和 textTopic 各贡献一项
        val events = listOf(
            mkEvent(UserActionType.PUBLISH_TWEET, "t1", now, mapOf(
                "imageTheme" to JsonPrimitive("风景"),
                "textTopic" to JsonPrimitive("摄影"),
                "captionLen" to JsonPrimitive(10),
                "imageCount" to JsonPrimitive(1),
            )),
        )
        val snapshot = BasicProfileAnalyzer.analyze(events, previous = null, now = now)
        assertTrue(snapshot.interestVector.containsKey("风景"))
        assertTrue(snapshot.interestVector.containsKey("摄影"))
        // textTopic 权重 = w * typeWeight * 1.5（TEXT_TOPIC_BOOST）
        // imageTheme 权重 = w * typeWeight * 1.0
        assertTrue(
            "textTopic 权重应高于 imageTheme",
            snapshot.interestVector["摄影"]!! > snapshot.interestVector["风景"]!!
        )
    }

    @Test
    fun `textTopics 次要主题出现在兴趣向量中但权重低于 textTopic`() {
        val events = listOf(
            mkEvent(UserActionType.PUBLISH_TWEET, "t1", now, mapOf(
                "textTopic" to JsonPrimitive("摄影"),
                "textTopics" to JsonArray(listOf(JsonPrimitive("旅行"), JsonPrimitive("器材"))),
                "captionLen" to JsonPrimitive(20),
                "imageCount" to JsonPrimitive(1),
            )),
        )
        val snapshot = BasicProfileAnalyzer.analyze(events, previous = null, now = now)
        assertTrue(snapshot.interestVector.containsKey("摄影"))
        assertTrue(snapshot.interestVector.containsKey("旅行"))
        assertTrue(snapshot.interestVector.containsKey("器材"))
        // textTopic 权重 = w * typeWeight * 1.5
        // textTopics 权重 = w * typeWeight * 0.5
        assertTrue(
            "textTopic 权重应高于 textTopics",
            snapshot.interestVector["摄影"]!! > snapshot.interestVector["旅行"]!!
        )
    }

    @Test
    fun `imageTheme 与 textTopic 同时存在同一主题时权重叠加`() {
        val events = listOf(
            mkEvent(UserActionType.PUBLISH_TWEET, "t1", now, mapOf(
                "imageTheme" to JsonPrimitive("摄影"),
                "textTopic" to JsonPrimitive("摄影"),
                "captionLen" to JsonPrimitive(10),
                "imageCount" to JsonPrimitive(1),
            )),
            mkEvent(UserActionType.PUBLISH_TWEET, "t2", now, mapOf(
                "imageTheme" to JsonPrimitive("风景"),
                "captionLen" to JsonPrimitive(10),
                "imageCount" to JsonPrimitive(1),
            )),
        )
        val snapshot = BasicProfileAnalyzer.analyze(events, previous = null, now = now)
        // 摄影 = imageTheme(w*1) + textTopic(w*1.5) = w*2.5
        // 风景 = imageTheme(w*1) = w*1
        assertTrue(snapshot.interestVector["摄影"]!! > snapshot.interestVector["风景"]!!)
    }

    @Test
    fun `证据链 topThemes 包含文本主题`() {
        val events = listOf(
            mkEvent(UserActionType.PUBLISH_TWEET, "t1", now, mapOf(
                "textTopic" to JsonPrimitive("摄影"),
                "captionLen" to JsonPrimitive(10),
                "imageCount" to JsonPrimitive(1),
            )),
            mkEvent(UserActionType.TWEET_VIEW, "t2", now, mapOf("imageTheme" to JsonPrimitive("风景"))),
        )
        val snapshot = BasicProfileAnalyzer.analyze(events, previous = null, now = now)
        val themes = snapshot.evidence.topThemes.map { it.theme }
        assertTrue("topThemes 应包含 textTopic 主题", themes.contains("摄影"))
    }

    @Test
    fun `无文本信号时不影响原有兴趣向量计算`() {
        val events = listOf(
            mkEvent(UserActionType.TWEET_VIEW, "t1", now, mapOf("imageTheme" to JsonPrimitive("风景"))),
        )
        val snapshot = BasicProfileAnalyzer.analyze(events, previous = null, now = now)
        assertEquals(1, snapshot.interestVector.size)
        assertTrue(snapshot.interestVector.containsKey("风景"))
    }

    // ---- helpers ----
    // mkEvent 已抽至 ProfileTestFixtures.kt（#292d），与本文件同包可直接调用。
}
