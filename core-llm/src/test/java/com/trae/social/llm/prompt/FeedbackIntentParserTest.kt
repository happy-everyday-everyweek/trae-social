package com.trae.social.llm.prompt

import com.trae.social.core.data.model.FeedbackAction
import com.trae.social.llm.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FeedbackIntentParser 单元测试（#146 第五层算法优化）。
 *
 * 覆盖：
 * - buildPrompt 生成 system + user 两条消息且包含可用主题/偏好/场景上下文。
 * - parseResponse 成功路径：高置信度意图 → directActions 非空且经 sanitize 校验。
 * - parseResponse 模糊路径：needsClarification=true 时 directActions 强制为空。
 * - parseResponse 回滚意图不进 directActions。
 * - parseResponse 非法/越界值经 sanitize 过滤。
 * - parseResponse 容错：markdown 包裹 / 非法 JSON / 缺字段 → null。
 * - fallback 返回 needsClarification=true 的空 ParsedIntent。
 * - parse() LLM 调用失败时降级到 fallback。
 * - hasDirectPath 计算属性正确。
 */
class FeedbackIntentParserTest {

    private val parser = FeedbackIntentParser()

    private val ctx = FeedbackIntentParser.ParseContext(
        availableThemes = listOf("technology", "sports", "food"),
        availablePreferences = listOf("morning_person", "minimalist"),
        activeHours = listOf(8, 9, 20, 21),
        activeScenarioIds = listOf(1, 2, 3, 4, 5, 6, 7, 8),
    )

    @Test
    fun `buildPrompt 返回 system 与 user 两条消息`() {
        val messages = parser.buildPrompt("把 tech 权重提到 0.8", ctx)
        assertEquals(2, messages.size)
        assertEquals(ChatMessage.Role.SYSTEM, messages[0].role)
        assertEquals(ChatMessage.Role.USER, messages[1].role)
    }

    @Test
    fun `system prompt 含可用主题与场景上下文`() {
        val messages = parser.buildPrompt("x", ctx)
        val system = messages[0].content
        assertTrue("应含可用主题 technology", system.contains("technology"))
        assertTrue("应含已有偏好 morning_person", system.contains("morning_person"))
        assertTrue("应含活跃时段", system.contains("8,9,20,21"))
        assertTrue("应含置信度阈值 0.8", system.contains("0.8"))
        assertTrue("应含可用 Action 类型 boost_theme", system.contains("boost_theme"))
    }

    @Test
    fun `user prompt 用边界标记包裹用户输入`() {
        val messages = parser.buildPrompt("少推点科技类内容", ctx)
        val user = messages[1].content
        assertTrue("应用 <<<USER_INPUT_START>>> 标记", user.contains("<<<USER_INPUT_START>>>"))
        assertTrue("应用 <<<USER_INPUT_END>>> 标记", user.contains("<<<USER_INPUT_END>>>"))
        assertTrue("应保留用户原文", user.contains("少推点科技类内容"))
    }

    @Test
    fun `parseResponse 高置信度意图生成 directActions`() {
        val raw = """
            {
              "normalizedText": "提升 technology 主题权重至 0.8",
              "detectedIntents": [
                {"actionType": "boost_theme", "targetEntity": "technology", "parameters": {"weight": "0.8"}, "confidence": 0.9}
              ],
              "entityResolutions": [
                {"userMention": "tech", "resolvedTo": "technology", "matchScore": 0.85}
              ],
              "ambiguityFlags": [],
              "needsClarification": false,
              "clarificationQuestion": null,
              "directActions": [
                {"type": "boost_theme", "theme": "technology", "weight": 0.8}
              ]
            }
        """.trimIndent()
        val parsed = parser.parseResponse(raw, ctx)
        assertNotNull(parsed)
        val intent = parsed!!
        assertEquals("提升 technology 主题权重至 0.8", intent.normalizedText)
        assertEquals(1, intent.detectedIntents.size)
        assertEquals("boost_theme", intent.detectedIntents[0].actionType)
        assertEquals(0.9, intent.detectedIntents[0].confidence, 0.001)
        assertEquals(1, intent.entityResolutions.size)
        assertEquals("technology", intent.entityResolutions[0].resolvedTo)
        assertFalse(intent.needsClarification)
        assertEquals(1, intent.directActions.size)
        val action = intent.directActions[0]
        assertTrue("应是 BoostTheme", action is FeedbackAction.BoostTheme)
        assertEquals("technology", (action as FeedbackAction.BoostTheme).theme)
        assertEquals(0.8, action.weight, 0.001)
        assertTrue("hasDirectPath 应为 true", intent.hasDirectPath)
    }

    @Test
    fun `parseResponse needsClarification=true 时 directActions 强制为空`() {
        val raw = """
            {
              "normalizedText": "调整下推荐",
              "detectedIntents": [
                {"actionType": "unknown", "targetEntity": null, "parameters": {}, "confidence": 0.3}
              ],
              "entityResolutions": [],
              "ambiguityFlags": ["intent_unclear"],
              "needsClarification": true,
              "clarificationQuestion": "你想调整推荐的哪方面？主题权重、场景开关还是别的？",
              "directActions": [
                {"type": "boost_theme", "theme": "technology", "weight": 0.7}
              ]
            }
        """.trimIndent()
        val parsed = parser.parseResponse(raw, ctx)
        assertNotNull(parsed)
        val intent = parsed!!
        assertTrue(intent.needsClarification)
        assertEquals("你想调整推荐的哪方面？主题权重、场景开关还是别的？", intent.clarificationQuestion)
        assertTrue(
            "needsClarification=true 时 directActions 必须为空",
            intent.directActions.isEmpty(),
        )
        assertFalse("hasDirectPath 应为 false", intent.hasDirectPath)
    }

    @Test
    fun `parseResponse 回滚 Action 不进 directActions`() {
        val raw = """
            {
              "normalizedText": "回滚到上一个版本",
              "detectedIntents": [
                {"actionType": "rollback_profile_version", "targetEntity": null, "parameters": {}, "confidence": 0.95}
              ],
              "entityResolutions": [],
              "ambiguityFlags": [],
              "needsClarification": false,
              "clarificationQuestion": null,
              "directActions": [
                {"type": "rollback_profile_version", "versionId": null, "aroundTimestamp": null, "narrativeKeyword": null}
              ]
            }
        """.trimIndent()
        val parsed = parser.parseResponse(raw, ctx)
        assertNotNull(parsed)
        val intent = parsed!!
        assertFalse(intent.needsClarification)
        assertEquals(
            "回滚 Action 不应在 directActions 中",
            0,
            intent.directActions.size,
        )
        assertFalse("纯回滚意图 hasDirectPath 应为 false（无直接可应用动作）", intent.hasDirectPath)
    }

    @Test
    fun `parseResponse BoostTheme weight 越界经 sanitize 钳制`() {
        val raw = """
            {
              "normalizedText": "提升 technology 到 5.0",
              "detectedIntents": [
                {"actionType": "boost_theme", "targetEntity": "technology", "parameters": {"weight": "5.0"}, "confidence": 0.9}
              ],
              "entityResolutions": [],
              "ambiguityFlags": ["value_out_of_range"],
              "needsClarification": false,
              "clarificationQuestion": null,
              "directActions": [
                {"type": "boost_theme", "theme": "technology", "weight": 5.0}
              ]
            }
        """.trimIndent()
        val parsed = parser.parseResponse(raw, ctx)
        assertNotNull(parsed)
        val action = parsed!!.directActions[0] as FeedbackAction.BoostTheme
        assertEquals(
            "weight 应被 sanitize 钳制到 1.0",
            1.0,
            action.weight,
            0.001,
        )
    }

    @Test
    fun `parseResponse DisableScenario scenarioId 越界被丢弃`() {
        val raw = """
            {
              "normalizedText": "关闭场景 99",
              "detectedIntents": [
                {"actionType": "disable_scenario", "targetEntity": "99", "parameters": {}, "confidence": 0.8}
              ],
              "entityResolutions": [],
              "ambiguityFlags": ["value_out_of_range"],
              "needsClarification": false,
              "clarificationQuestion": null,
              "directActions": [
                {"type": "disable_scenario", "scenarioId": 99}
              ]
            }
        """.trimIndent()
        val parsed = parser.parseResponse(raw, ctx)
        assertNotNull(parsed)
        assertTrue(
            "scenarioId=99 越界，sanitize 拒绝，directActions 应为空",
            parsed!!.directActions.isEmpty(),
        )
    }

    @Test
    fun `parseResponse SetActiveHours 过滤越界小时`() {
        val raw = """
            {
              "normalizedText": "活跃时段改为 7 8 25",
              "detectedIntents": [
                {"actionType": "set_active_hours", "targetEntity": null, "parameters": {}, "confidence": 0.85}
              ],
              "entityResolutions": [],
              "ambiguityFlags": [],
              "needsClarification": false,
              "clarificationQuestion": null,
              "directActions": [
                {"type": "set_active_hours", "hours": [7, 8, 25]}
              ]
            }
        """.trimIndent()
        val parsed = parser.parseResponse(raw, ctx)
        assertNotNull(parsed)
        val action = parsed!!.directActions[0] as FeedbackAction.SetActiveHours
        assertEquals(
            "越界小时 25 应被过滤，仅保留 7 与 8",
            listOf(7, 8),
            action.hours,
        )
    }

    @Test
    fun `parseResponse 多意图全部进 directActions`() {
        val raw = """
            {
              "normalizedText": "提升 technology 到 0.8，关闭场景 6，新增偏好 morning",
              "detectedIntents": [
                {"actionType": "boost_theme", "targetEntity": "technology", "parameters": {"weight": "0.8"}, "confidence": 0.9},
                {"actionType": "disable_scenario", "targetEntity": "6", "parameters": {}, "confidence": 0.95},
                {"actionType": "add_preference", "targetEntity": "morning", "parameters": {}, "confidence": 0.85}
              ],
              "entityResolutions": [],
              "ambiguityFlags": [],
              "needsClarification": false,
              "clarificationQuestion": null,
              "directActions": [
                {"type": "boost_theme", "theme": "technology", "weight": 0.8},
                {"type": "disable_scenario", "scenarioId": 6},
                {"type": "add_preference", "preference": "morning"}
              ]
            }
        """.trimIndent()
        val parsed = parser.parseResponse(raw, ctx)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.directActions.size)
        assertTrue(parsed.hasDirectPath)
    }

    @Test
    fun `parseResponse 容错 markdown 代码块包裹`() {
        val raw = """
            ```json
            {
              "normalizedText": "test",
              "detectedIntents": [],
              "entityResolutions": [],
              "ambiguityFlags": [],
              "needsClarification": false,
              "clarificationQuestion": null,
              "directActions": []
            }
            ```
        """.trimIndent()
        val parsed = parser.parseResponse(raw, ctx)
        assertNotNull("markdown 包裹的 JSON 应能被宽松提取", parsed)
        assertEquals("test", parsed!!.normalizedText)
    }

    @Test
    fun `parseResponse 非法 JSON 返回 null`() {
        val raw = "这不是 JSON"
        val parsed = parser.parseResponse(raw, ctx)
        assertNull(parsed)
    }

    @Test
    fun `parseResponse 缺 normalizedText 字段返回 null`() {
        val raw = """
            {
              "detectedIntents": [],
              "entityResolutions": [],
              "ambiguityFlags": [],
              "needsClarification": false,
              "clarificationQuestion": null,
              "directActions": []
            }
        """.trimIndent()
        val parsed = parser.parseResponse(raw, ctx)
        assertNull("缺 normalizedText 必填字段应返回 null", parsed)
    }

    @Test
    fun `parseResponse detectedIntents 置信度钳制到 0-1`() {
        val raw = """
            {
              "normalizedText": "x",
              "detectedIntents": [
                {"actionType": "boost_theme", "targetEntity": "technology", "parameters": {}, "confidence": 1.5},
                {"actionType": "suppress_theme", "targetEntity": "sports", "parameters": {}, "confidence": -0.3}
              ],
              "entityResolutions": [],
              "ambiguityFlags": [],
              "needsClarification": false,
              "clarificationQuestion": null,
              "directActions": []
            }
        """.trimIndent()
        val parsed = parser.parseResponse(raw, ctx)
        assertNotNull(parsed)
        assertEquals(1.0, parsed!!.detectedIntents[0].confidence, 0.001)
        assertEquals(0.0, parsed.detectedIntents[1].confidence, 0.001)
    }

    @Test
    fun `parseResponse entityResolutions resolvedTo 为 null 表示无法归一`() {
        val raw = """
            {
              "normalizedText": "提升 xyz 主题",
              "detectedIntents": [
                {"actionType": "boost_theme", "targetEntity": "xyz", "parameters": {}, "confidence": 0.7}
              ],
              "entityResolutions": [
                {"userMention": "xyz", "resolvedTo": null, "matchScore": 0.0}
              ],
              "ambiguityFlags": ["theme_not_found"],
              "needsClarification": false,
              "clarificationQuestion": null,
              "directActions": []
            }
        """.trimIndent()
        val parsed = parser.parseResponse(raw, ctx)
        assertNotNull(parsed)
        val resolution = parsed!!.entityResolutions[0]
        assertEquals("xyz", resolution.userMention)
        assertNull(resolution.resolvedTo)
        assertEquals(0.0, resolution.matchScore, 0.001)
    }

    @Test
    fun `fallback 返回 needsClarification=true 的空 ParsedIntent`() {
        val fallback = parser.fallback("用户消息")
        assertEquals("用户消息", fallback.normalizedText)
        assertTrue(fallback.detectedIntents.isEmpty())
        assertTrue(fallback.entityResolutions.isEmpty())
        assertEquals(listOf("pre_parse_unavailable"), fallback.ambiguityFlags)
        assertTrue(fallback.needsClarification)
        assertNull(fallback.clarificationQuestion)
        assertTrue(fallback.directActions.isEmpty())
        assertFalse("fallback 不应走直接路径", fallback.hasDirectPath)
    }

    @Test
    fun `parse LLM 调用抛异常时降级到 fallback`() = runBlocking {
        val failingClient = FailingLlmClient()
        val parsed = parser.parse(failingClient, "用户消息", ctx)
        assertTrue("LLM 失败应返回 fallback（needsClarification=true）", parsed.needsClarification)
        assertTrue(parsed.directActions.isEmpty())
        assertEquals(listOf("pre_parse_unavailable"), parsed.ambiguityFlags)
    }

    @Test
    fun `parse LLM 返回非法 JSON 时降级到 fallback`() = runBlocking {
        val garbageClient = StubLlmClient("这不是 JSON")
        val parsed = parser.parse(garbageClient, "用户消息", ctx)
        assertTrue("LLM 返回非法 JSON 应降级到 fallback", parsed.needsClarification)
        assertEquals(listOf("pre_parse_unavailable"), parsed.ambiguityFlags)
    }

    @Test
    fun `parse LLM 返回合法 JSON 时正常解析`() = runBlocking {
        val validJson = """
            {
              "normalizedText": "提升 technology 权重",
              "detectedIntents": [
                {"actionType": "boost_theme", "targetEntity": "technology", "parameters": {"weight": "0.8"}, "confidence": 0.9}
              ],
              "entityResolutions": [],
              "ambiguityFlags": [],
              "needsClarification": false,
              "clarificationQuestion": null,
              "directActions": [
                {"type": "boost_theme", "theme": "technology", "weight": 0.8}
              ]
            }
        """.trimIndent()
        val stubClient = StubLlmClient(validJson)
        val parsed = parser.parse(stubClient, "提升 tech 权重", ctx)
        assertEquals("提升 technology 权重", parsed.normalizedText)
        assertEquals(1, parsed.directActions.size)
        assertTrue(parsed.hasDirectPath)
    }
}

/** 总是抛异常的 LlmClient 桩，用于测试 LLM 不可用时的降级。 */
private class FailingLlmClient : com.trae.social.llm.LlmClient {
    override val provider: com.trae.social.core.data.config.LlmProvider
        get() = com.trae.social.core.data.config.LlmProvider.OPENAI

    override suspend fun chat(
        messages: List<ChatMessage>,
        config: com.trae.social.llm.ChatConfig,
    ): kotlinx.coroutines.flow.Flow<String> = throw RuntimeException("LLM unavailable")

    override suspend fun chatSync(
        messages: List<ChatMessage>,
        config: com.trae.social.llm.ChatConfig,
    ): String = throw RuntimeException("LLM unavailable")

    override suspend fun ping(): Boolean = false
}

/** 返回固定字符串的 LlmClient 桩。 */
private class StubLlmClient(private val response: String) : com.trae.social.llm.LlmClient {
    override val provider: com.trae.social.core.data.config.LlmProvider
        get() = com.trae.social.core.data.config.LlmProvider.OPENAI

    override suspend fun chat(
        messages: List<ChatMessage>,
        config: com.trae.social.llm.ChatConfig,
    ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flowOf(response)

    override suspend fun chatSync(
        messages: List<ChatMessage>,
        config: com.trae.social.llm.ChatConfig,
    ): String = response

    override suspend fun ping(): Boolean = true
}
