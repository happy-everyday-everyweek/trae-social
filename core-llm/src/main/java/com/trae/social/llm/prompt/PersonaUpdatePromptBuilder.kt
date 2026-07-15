package com.trae.social.llm.prompt

import com.trae.social.llm.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 人设动态字段更新 Prompt 构建器（SubTask 5.3）。
 *
 * 基于账号最近一周的活动，让 LLM 演进其人生经历、工作信息与情绪状态，
 * 同时通过字符级 Jaccard 相似度校验防止人设突变（RISK-2）。
 *
 * 风险控制：
 * - RISK-2（人设漂移）：system prompt 要求保持一致性，[shouldRollback] 对低相似度结果回退。
 * - RISK-13（JSON 解析）：[parsePersonaUpdate] 宽松解析，失败返回 null。
 */
class PersonaUpdatePromptBuilder {

    /**
     * 账号当前动态字段。
     *
     * @param lifeStory 人生经历。
     * @param workInfo 工作信息。
     * @param mood 当前情绪状态。
     * @param relationshipNetwork 关系网络描述。
     */
    data class PersonaDynamicInput(
        val lifeStory: String,
        val workInfo: String,
        val mood: String,
        val relationshipNetwork: String,
    )

    /**
     * 人设更新结果。
     */
    data class PersonaUpdateResult(
        val lifeStory: String,
        val workInfo: String,
        val mood: String,
        // #74：relationshipNetwork 原为死字段，现由 LLM 生成并写入
        val relationshipNetwork: List<String> = emptyList(),
    )

    /**
     * 构建对话消息列表。
     *
     * @param current 当前动态字段。
     * @param recentEvents 最近一周该账号的推文与互动事件描述列表。
     * @param userInterests 用户兴趣 Top 主题（#146 A/E 场景 7 personaCoEvolve）；
     *   非空时在 user prompt 追加 【用户兴趣画像】 段，引导人设演进向用户兴趣方向共演化，
     *   提升虚拟账号与用户的话题共鸣度；为空时走 control 路径（不注入）。
     * @return system + user 两条消息。
     */
    fun build(
        current: PersonaDynamicInput,
        recentEvents: List<String>,
        userInterests: List<String> = emptyList(),
    ): List<ChatMessage> {
        val system = buildSystemPrompt()
        val user = buildUserPrompt(current, recentEvents, userInterests)
        return listOf(
            ChatMessage(ChatMessage.Role.SYSTEM, system),
            ChatMessage(ChatMessage.Role.USER, user),
        )
    }

    private fun buildSystemPrompt(): String {
        return buildString {
            appendLine("你是人设演进引擎。基于角色最近的活动，更新其人生经历、工作信息与情绪状态。")
            appendLine("保持人设一致性，不要突变：新内容应是在原内容基础上的自然推进，而非推翻重写。")
            appendLine("输出前检查内容不包含暴力、仇恨、色情或对真实人物的虚假陈述。")
        }
    }

    private fun buildUserPrompt(
        current: PersonaDynamicInput,
        recentEvents: List<String>,
        userInterests: List<String>,
    ): String {
        return buildString {
            appendLine("【当前动态字段】")
            appendLine("- 人生经历：${current.lifeStory}")
            appendLine("- 工作信息：${current.workInfo}")
            appendLine("- 当前情绪：${current.mood}")
            appendLine("- 关系网络：${current.relationshipNetwork}")
            appendLine()
            appendLine("【最近一周活动事件】")
            if (recentEvents.isEmpty()) {
                appendLine("（暂无近期事件）")
            } else {
                recentEvents.forEachIndexed { i, e -> appendLine("${i + 1}. $e") }
            }
            // #146 A/E 场景 7 personaCoEvolve：driven 组注入用户兴趣画像，
            // 引导虚拟账号人设向用户兴趣方向自然共演化，提升话题共鸣度
            if (userInterests.isNotEmpty()) {
                appendLine()
                appendLine("【用户兴趣画像】")
                appendLine("用户近期关注主题：${userInterests.joinToString("、")}")
                appendLine("在人设一致性前提下，可适度让角色的经历/工作/兴趣向上述用户关注主题靠拢，")
                appendLine("使角色与用户有更多共同话题，提升互动自然度与共鸣感。")
                appendLine("注意：不要生硬堆砌关键词，仅在角色背景合理时自然融入；")
                appendLine("若角色定位与用户兴趣完全无关，保持角色原有人设不变。")
            }
            appendLine()
            appendLine("请输出 JSON：{\"lifeStory\": \"...\", \"workInfo\": \"...\", \"mood\": \"...\", \"relationshipNetwork\": [\"...\"]}。")
            appendLine("不要输出 JSON 以外的任何说明文字。")
        }
    }

    companion object {

        private val parser: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /**
         * 宽松解析人设更新结果。
         *
         * 提取 JSON 对象后按字段读取；任一关键字段缺失返回 null。
         */
        fun parsePersonaUpdate(rawText: String): PersonaUpdateResult? {
            val jsonStr = PromptUtils.extractJson(rawText) ?: return null
            val obj = PromptUtils.safeParseJson(jsonStr, parser) ?: return null

            val lifeStory = obj["lifeStory"]?.let { (it as? JsonPrimitive)?.content }
                ?: return null
            val workInfo = obj["workInfo"]?.let { (it as? JsonPrimitive)?.content }
                ?: return null
            val mood = obj["mood"]?.let { (it as? JsonPrimitive)?.content }
                ?: return null
            // #74：解析 relationshipNetwork 数组，缺失或格式不符时降级为空列表
            val relationshipNetwork = obj["relationshipNetwork"]?.let { element ->
                (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            } ?: emptyList()

            return PersonaUpdateResult(
                lifeStory = lifeStory,
                workInfo = workInfo,
                mood = mood,
                relationshipNetwork = relationshipNetwork,
            )
        }

        /**
         * 计算两段文本的 Jaccard 相似度，作为 embedding cosine 的轻量替代。
         *
         * #85：改用 bigram（字符二元组）替代单字符集合，捕捉局部词序信息，
         * 对短文本突变更敏感。公式：|A ∩ B| / |A ∪ B|，取值 0.0-1.0。
         * 空字符串视为空集合：两空串相似度记为 1.0（视为相同）；
         * 一空一非空相似度记为 0.0。
         * 长度不足 2 的字符串退化为单元素集合。
         *
         * 不依赖任何 NLP 库，满足"不引入额外依赖"约束。
         *
         * P2 修复：函数名从 cosineSimilarity 改为 jaccardSimilarity，名实相符。
         */
        fun jaccardSimilarity(a: String, b: String): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            // #85：改用 bigram（字符二元组）替代单字符集合，捕捉局部词序信息
            val setA = if (a.length >= 2) a.windowed(2).toSet() else setOf(a)
            val setB = if (b.length >= 2) b.windowed(2).toSet() else setOf(b)
            if (setA.isEmpty() && setB.isEmpty()) return 1.0
            val intersection = setA.intersect(setB).size
            val union = setA.union(setB).size
            if (union == 0) return 1.0
            return intersection.toDouble() / union.toDouble()
        }

        /**
         * 判定是否应回退更新：当新旧文本相似度低于 [threshold] 时认为发生突变，需回退。
         *
         * @param old 原文本。
         * @param new 新文本。
         * @param threshold 相似度阈值，低于该值判定突变，默认 0.5（与 checklist RISK-2 要求一致）。
         * @return true 表示应回退（保留旧值）。
         */
        fun shouldRollback(old: String, new: String, threshold: Double = 0.5): Boolean {
            // #73：旧值为空时视为首次更新，不触发回退
            if (old.isEmpty()) return false
            return jaccardSimilarity(old, new) < threshold
        }
    }
}
