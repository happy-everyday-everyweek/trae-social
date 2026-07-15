package com.trae.social.llm.prompt

import com.trae.social.core.data.model.EventSummary
import com.trae.social.core.data.model.FeedbackEffect
import com.trae.social.core.data.model.FeedbackWeights
import com.trae.social.core.data.model.OverrideRecord
import com.trae.social.core.data.model.UserFeedbackSummary
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.llm.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LLM 深度画像 Prompt 构建器（#146 第三层）。
 *
 * 将基础分析快照 + 事件摘要 + 反哺效果 + 用户反馈 + 当前覆盖 + 上轮 narrative 聚合为
 * system + user 两条消息，输出可解析为 [Result]（含 identityHypothesis /
 * feedbackWeights / overrideAcknowledgment）。
 *
 * 约束（system prompt）：
 * - 用户显式反馈与覆盖为最高优先信号，画像演化须尊重用户意志。
 * - narrative 须体现用户反馈的影响。
 * - 低置信度维度提示：prompt 标注各维度 confidence，引导 LLM 对低置信度维度输出更保守的
 *   feedbackWeights。
 *
 * 复用 [PromptUtils] 宽松解析 + [PersonaUpdatePromptBuilder.jaccardSimilarity] /
 * [PersonaUpdatePromptBuilder.shouldRollback]（阈值 0.4）做 narrative 突变校验。
 */
class UserProfilePromptBuilder {

    /**
     * LLM 画像 Prompt 输入（由 [com.trae.social.core.profiling.analysis.UserProfileAggregator] 聚合）。
     */
    data class Input(
        val snapshot: UserProfileSnapshot,
        val eventSummary: EventSummary,
        val feedbackEffect: FeedbackEffect,
        val userFeedback: UserFeedbackSummary,
        val activeOverrides: List<OverrideRecord>,
        val previousNarrative: String?,
    )

    /**
     * LLM 画像解析结果，落库为 [com.trae.social.core.data.model.UserProfileVersion]。
     */
    data class Result(
        val identityHypothesis: String,
        val personalityTraits: List<String>,
        val contentPreferences: List<String>,
        val socialStyle: String,
        val activityProfile: String,
        val engagementLevel: String,
        val feedbackWeights: FeedbackWeights,
        val narrative: String,
        val overrideAcknowledgment: List<String>,
    )

    /**
     * 构建对话消息列表（system + user）。
     *
     * 用户反馈优先级最高：user prompt 中先列用户反馈与覆盖，再列基础快照与反哺效果。
     */
    fun build(input: Input): List<ChatMessage> {
        val system = buildSystemPrompt()
        val user = buildUserPrompt(input)
        return listOf(
            ChatMessage(ChatMessage.Role.SYSTEM, system),
            ChatMessage(ChatMessage.Role.USER, user),
        )
    }

    private fun buildSystemPrompt(): String = buildString {
        appendLine("你是用户身份建模引擎。基于基础分析快照、事件摘要、反哺效果与用户反馈，输出用户画像。")
        appendLine("输出纯 JSON：{\"identityHypothesis\":\"...\",\"personalityTraits\":[...],\"contentPreferences\":[...],\"socialStyle\":\"...\",\"activityProfile\":\"...\",\"engagementLevel\":\"...\",\"feedbackWeights\":{...},\"narrative\":\"...\",\"overrideAcknowledgment\":[...]}。")
        appendLine("不要输出 JSON 以外的任何说明文字。")
        appendLine()
        appendLine("约束：")
        appendLine("- 用户显式反馈与覆盖为最高优先信号，画像演化须尊重用户意志。")
        appendLine("- narrative 须体现用户反馈的影响，长度 100-300 字。")
        appendLine("- overrideAcknowledgment 简述本轮如何吸纳用户覆盖（每条 <=50 字）。")
        appendLine("- 低置信度维度输出更保守的 feedbackWeights（接近 0）。")
        appendLine("- feedbackWeights 各字段取值 0-1。")
        appendLine("- feedbackWeights 字段：topicBias / accountPriority / interactionAffinity / commentPersona / feedBoost / followRecommend / personaCoEvolve / interactionTiming。")
    }

    private fun buildUserPrompt(input: Input): String = buildString {
        // 用户反馈层（优先级最高，先列）
        appendLine("【用户反馈】")
        if (input.userFeedback.recentMessages.isEmpty()) {
            appendLine("（暂无用户反馈）")
        } else {
            input.userFeedback.recentMessages.take(10).forEachIndexed { i, m ->
                appendLine("${i + 1}. [${m.role}] ${m.content.take(120)}")
            }
        }
        appendLine()
        appendLine("【当前生效覆盖】")
        if (input.activeOverrides.isEmpty()) {
            appendLine("（无覆盖）")
        } else {
            input.activeOverrides.forEach {
                appendLine("- ${it.type.id} key=${it.key} value=${it.value} reason=${it.reason.take(60)}")
            }
        }
        appendLine()
        appendLine("【基础分析快照】")
        appendLine("- 活跃时段：${input.snapshot.activeHours}")
        appendLine("- 兴趣向量 Top5：${input.snapshot.evidence.topThemes.joinToString { "${it.theme}=${"%.2f".format(it.weight)}" }}")
        appendLine("- 互动倾向：like=${pct(input.snapshot.interactionTendency.likeRate)} comment=${pct(input.snapshot.interactionTendency.commentRate)} retweet=${pct(input.snapshot.interactionTendency.retweetRate)} bookmark=${pct(input.snapshot.interactionTendency.bookmarkRate)}")
        appendLine("- 浏览深度：avgDwell=${input.snapshot.browseDepth.avgDwellMs}ms tweetsPerSession=${"%.1f".format(input.snapshot.browseDepth.tweetsPerSession)}")
        appendLine("- 发帖节奏：${input.eventSummary.postingCadenceSummary}")
        appendLine("- 周期性：${input.eventSummary.periodicitySummary}")
        appendLine()
        appendLine("【置信度提示】")
        appendLine("- interestVector=${pct(input.snapshot.confidence.interestVector)} interactionTendency=${pct(input.snapshot.confidence.interactionTendency)} activeHours=${pct(input.snapshot.confidence.activeHours)} socialStyle=${pct(input.snapshot.confidence.socialStyle)} overall=${pct(input.snapshot.confidence.overall)}")
        appendLine("- 提示：低置信度（<0.3）维度的 feedbackWeights 应输出更保守的值（接近 0）。")
        appendLine()
        appendLine("【反哺效果（A/B delta）】")
        if (input.feedbackEffect.scenarioDeltas.isEmpty()) {
            appendLine("（暂无 A/B 数据）")
        } else {
            input.feedbackEffect.scenarioDeltas.entries.sortedBy { it.key }.forEach { (s, d) ->
                val tag = if (d < 0) "[负反馈降权]" else "[正向]"
                appendLine("- 场景$s: delta=${"%.3f".format(d)} $tag")
            }
        }
        if (input.feedbackEffect.negativeScenarios.isNotEmpty()) {
            appendLine("- 负反馈场景：${input.feedbackEffect.negativeScenarios}")
        }
        appendLine()
        appendLine("【事件摘要】")
        appendLine("- 总事件数：${input.eventSummary.totalEvents}")
        appendLine("- Top 主题：${input.eventSummary.topThemes.joinToString { "${it.theme}(${it.interactions}互动)" }}")
        appendLine("- Top 活跃小时：${input.eventSummary.topActiveHours.joinToString { "${it.hour}h(${it.eventCount})" }}")
        appendLine()
        appendLine("【上一轮 narrative】")
        appendLine(input.previousNarrative?.take(300) ?: "（首轮画像）")
    }

    private fun pct(v: Double): String = "${(v * 100).toInt()}%"

    companion object {

        private val parser: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /** narrative 突变阈值（与 issue #146 一致）。 */
        const val NARRATIVE_ROLLBACK_THRESHOLD = 0.4

        /**
         * 宽松解析 LLM 画像输出为 [Result]。
         *
         * 提取 JSON 对象后按字段读取；关键字段缺失或类型不符返回 null。
         * feedbackWeights 缺失时降级为 ZERO（避免阻塞画像版本生成）。
         */
        fun parseUserProfile(rawText: String): Result? {
            val jsonStr = PromptUtils.extractJson(rawText) ?: return null
            val obj = PromptUtils.safeParseJson(jsonStr, parser) ?: return null

            val identityHypothesis = obj.stringField("identityHypothesis") ?: return null
            val personalityTraits = obj.stringList("personalityTraits")
            val contentPreferences = obj.stringList("contentPreferences")
            val socialStyle = obj.stringField("socialStyle") ?: return null
            val activityProfile = obj.stringField("activityProfile") ?: return null
            val engagementLevel = obj.stringField("engagementLevel") ?: return null
            val feedbackWeights = parseWeights(obj["feedbackWeights"] as? JsonObject) ?: FeedbackWeights.ZERO
            val narrative = obj.stringField("narrative") ?: return null
            val overrideAcknowledgment = obj.stringList("overrideAcknowledgment")
            return Result(
                identityHypothesis = identityHypothesis,
                personalityTraits = personalityTraits,
                contentPreferences = contentPreferences,
                socialStyle = socialStyle,
                activityProfile = activityProfile,
                engagementLevel = engagementLevel,
                feedbackWeights = feedbackWeights,
                narrative = narrative,
                overrideAcknowledgment = overrideAcknowledgment,
            )
        }

        /**
         * 判定 narrative 是否突变：基于 [PersonaUpdatePromptBuilder.jaccardSimilarity]，
         * 阈值 [NARRATIVE_ROLLBACK_THRESHOLD]（0.4）。
         *
         * 旧 narrative 为空（首轮）不触发回退。
         */
        fun shouldRollbackNarrative(old: String, new: String): Boolean {
            if (old.isEmpty()) return false
            return PersonaUpdatePromptBuilder.jaccardSimilarity(old, new) < NARRATIVE_ROLLBACK_THRESHOLD
        }

        private fun parseWeights(obj: JsonObject?): FeedbackWeights? {
            if (obj == null) return null
            return FeedbackWeights(
                topicBias = obj.doubleField("topicBias") ?: 0.0,
                accountPriority = obj.doubleField("accountPriority") ?: 0.0,
                interactionAffinity = obj.doubleField("interactionAffinity") ?: 0.0,
                commentPersona = obj.doubleField("commentPersona") ?: 0.0,
                feedBoost = obj.doubleField("feedBoost") ?: 0.0,
                followRecommend = obj.doubleField("followRecommend") ?: 0.0,
                personaCoEvolve = obj.doubleField("personaCoEvolve") ?: 0.0,
                interactionTiming = obj.doubleField("interactionTiming") ?: 0.0,
            )
        }

        private fun JsonObject.stringField(key: String): String? =
            (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

        private fun JsonObject.doubleField(key: String): Double? =
            (this[key] as? JsonPrimitive)?.let {
                runCatching { it.content.toDouble() }.getOrNull()
            }

        private fun JsonObject.stringList(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    }
}
