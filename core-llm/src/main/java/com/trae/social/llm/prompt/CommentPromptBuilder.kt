package com.trae.social.llm.prompt

import com.trae.social.llm.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import timber.log.Timber

/**
 * 评论生成 Prompt 构建器（SubTask 5.2）。
 *
 * 一次调用为多个评论者人设批量生成评论/点赞/转发动作，降低 LLM 调用成本。
 *
 * 风险控制：
 * - RISK-13（JSON 解析）：[parseCommentResults] 宽松解析数组，单条解析失败跳过而非整体失败。
 */
class CommentPromptBuilder {

    /**
     * 被评推文输入。
     *
     * @param text 推文正文。
     * @param authorName 原作者显示名。
     * @param authorProfession 原作者职业，用于人设简介。
     */
    data class TweetInput(
        val text: String,
        val authorName: String,
        val authorProfession: String,
    )

    /**
     * 评论结果。
     *
     * @param commenterIndex 评论者在输入列表中的下标，从 0 开始。
     * @param text 评论正文；LIKE/RETWEET 类型可为空字符串。
     * @param type 动作类型：COMMENT / LIKE / RETWEET。
     */
    data class CommentResult(
        val commenterIndex: Int,
        val text: String,
        val type: CommentType,
    )

    enum class CommentType { COMMENT, LIKE, RETWEET }

    /**
     * 用户口味提示（#146 A/E 场景 4 commentPersona）。
     *
     * 当 driven 组启用场景 4 时，由 [InteractionWorker] 收集用户兴趣 Top 主题，
     * 注入到评论 prompt，使 AI 评论文本在主题、措辞、情感倾向上更贴近用户口味；
     * control 组不注入，保留原始评论风格，供 computeFeedbackEffect 做 A/B 回测
     * 评论质量与互动率 delta。
     *
     * @param topThemes 用户兴趣 Top 主题列表（来源：snapshot.evidence.topThemes + interestVector keys）。
     * @param topInterestWeights 主题到兴趣权重的映射（用于提示 LLM 关注权重高的主题）。
     * @param narrative 用户画像叙事摘要，供 LLM 理解用户身份背景，可选。
     */
    data class UserTasteHint(
        val topThemes: List<String>,
        val topInterestWeights: Map<String, Double>,
        val narrative: String? = null,
    )

    /**
     * 构建对话消息列表。
     *
     * @param tweet 被评推文。
     * @param commenters 评论者人设列表，建议 3-5 个。
     * @param userTaste 用户口味提示；非空时启用 #146 场景 4 driven 路径，
     *   在 user prompt 末尾追加 【用户口味提示】 段；为空时走 control 路径。
     * @return system + user 两条消息。
     */
    fun build(
        tweet: TweetInput,
        commenters: List<TweetPromptBuilder.PersonaInput>,
        userTaste: UserTasteHint? = null,
    ): List<ChatMessage> {
        require(commenters.isNotEmpty()) { "评论者人设列表不能为空" }
        val system = buildSystemPrompt()
        val user = buildUserPrompt(tweet, commenters, userTaste)
        return listOf(
            ChatMessage(ChatMessage.Role.SYSTEM, system),
            ChatMessage(ChatMessage.Role.USER, user),
        )
    }

    private fun buildSystemPrompt(): String {
        return buildString {
            appendLine("你将模拟多个不同人设的评论者。为每位评论者生成一条符合其人设的评论。")
            appendLine("评论必须符合对应评论者的语言风格、价值观与情绪，不得偏离人设。")
            appendLine(PromptUtils.DATA_NOT_INSTRUCTIONS_CLAUSE)
            appendLine("输出前检查内容不包含暴力、仇恨、色情或对真实人物的虚假陈述。")
        }
    }

    private fun buildUserPrompt(
        tweet: TweetInput,
        commenters: List<TweetPromptBuilder.PersonaInput>,
        userTaste: UserTasteHint? = null,
    ): String {
        // #304：被评推文 / 人设字段 / 用户口味均为外部可控内容，插值前净化
        val safeAuthorName = PromptUtils.sanitizeForPrompt(tweet.authorName, 60)
        val safeAuthorProfession = PromptUtils.sanitizeForPrompt(tweet.authorProfession, 60)
        val safeTweetText = PromptUtils.sanitizeForPrompt(tweet.text, 280)
        return buildString {
            appendLine("【被评推文】")
            appendLine("作者：$safeAuthorName（职业：$safeAuthorProfession）")
            appendLine("正文：$safeTweetText")
            appendLine()
            appendLine("【原作者人设简介】")
            appendLine("姓名：$safeAuthorName；职业：$safeAuthorProfession")
            appendLine()
            appendLine("【评论者人设列表】")
            commenters.forEachIndexed { i, p ->
                val name = PromptUtils.sanitizeForPrompt(p.displayName, 60)
                val prof = PromptUtils.sanitizeForPrompt(p.profession, 60)
                val age = PromptUtils.sanitizeForPrompt(p.ageRange, 20)
                val style = PromptUtils.sanitizeForPrompt(p.languageStyle, 60)
                val vals = PromptUtils.sanitizeForPrompt(p.values, 120)
                val catch = PromptUtils.sanitizeForPrompt(p.catchphrase, 80)
                val mood = PromptUtils.sanitizeForPrompt(p.recentMood, 60)
                appendLine(" #$i $name（职业：$prof，年龄：$age，风格：$style，价值观：$vals，口癖：$catch，情绪：$mood）")
            }
            appendLine()
            // #146 A/E 场景 4：driven 组注入用户口味提示，引导评论文本贴近用户兴趣
            if (userTaste != null) {
                appendLine("【用户口味提示】")
                if (userTaste.topThemes.isNotEmpty()) {
                    val themes = userTaste.topThemes.joinToString("、") { PromptUtils.sanitizeForPrompt(it, 40) }
                    appendLine("用户兴趣 Top 主题：$themes")
                }
                if (userTaste.topInterestWeights.isNotEmpty()) {
                    val weightedTop = userTaste.topInterestWeights.entries
                        .sortedByDescending { it.value }
                        .take(5)
                        .joinToString("、") {
                            "${PromptUtils.sanitizeForPrompt(it.key, 40)}(${"%.2f".format(it.value)})"
                        }
                    appendLine("高权重主题：$weightedTop")
                }
                if (!userTaste.narrative.isNullOrBlank()) {
                    appendLine("用户背景：${PromptUtils.sanitizeForPrompt(userTaste.narrative, 120)}")
                }
                appendLine("生成评论时，在保持评论者人设一致的前提下，可适度贴合用户兴趣主题与语言偏好。")
                appendLine("注意：不要强行硬塞用户兴趣关键词；只在主题自然相关时融入，避免生硬感。")
                appendLine()
            }
            appendLine("请输出 JSON 数组：[{\"commenterIndex\": 0, \"text\": \"评论内容\", \"type\": \"COMMENT/LIKE/RETWEET\"}]。")
            appendLine("约束：每条评论 text 不超过 100 字符；COMMENT 必带 text；LIKE/RETWEET 的 text 可为空字符串。")
            appendLine("commenterIndex 必须对应上述评论者列表的下标。不要输出 JSON 以外的任何说明文字。")
        }
    }

    companion object {

        private val parser: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /**
         * 宽松解析评论结果数组。
         *
         * 提取首个 JSON 数组片段后逐条解析；单条字段缺失或类型不符时跳过该条，
         * 不影响其余条目。整体无法解析时返回空列表。
         *
         * @param rawText LLM 返回的原始文本。
         * @param commenterCount 评论者数量；传入时用于校验 commenterIndex 上界，
         *   越界条目在 builder 层即被过滤并记录 warn 日志，避免下游静默丢弃、
         *   浪费已消耗的 LLM token。默认 [Int.MAX_VALUE] 表示不校验上界（向后兼容）。
         */
        @JvmOverloads
        fun parseCommentResults(
            rawText: String,
            commenterCount: Int = Int.MAX_VALUE,
        ): List<CommentResult> {
            val jsonStr = PromptUtils.extractJsonArray(rawText) ?: return emptyList()
            val arr = PromptUtils.safeParseJsonArray(jsonStr, parser) ?: return emptyList()
            val results = mutableListOf<CommentResult>()
            var outOfBounds = 0
            for (element in arr) {
                val obj = element as? JsonObject ?: continue
                val parsed = parseOne(obj, commenterCount)
                if (parsed == null) {
                    // parseOne 返回 null 的原因之一是越界；统计用于日志。
                    if (obj["commenterIndex"] != null) outOfBounds++
                    continue
                }
                results.add(parsed)
            }
            if (outOfBounds > 0) {
                Timber.w("评论解析：commenterIndex 越界被过滤 %d 条（评论者数=%d）", outOfBounds, commenterCount)
            }
            return results
        }

        private fun parseOne(obj: JsonObject, commenterCount: Int): CommentResult? {
            val index = (obj["commenterIndex"] as? JsonPrimitive)?.let {
                runCatching { it.int }.getOrNull()
            } ?: return null
            // 校验上下界：下界 <0 或上界 >= commenterCount 均视为非法，在 builder 层过滤。
            if (index < 0) return null
            if (commenterCount != Int.MAX_VALUE && index >= commenterCount) return null

            val text = (obj["text"] as? JsonPrimitive)?.content ?: ""

            val type = (obj["type"] as? JsonPrimitive)?.content?.let { parseType(it) }
                ?: CommentType.COMMENT

            return CommentResult(
                commenterIndex = index,
                text = text,
                type = type,
            )
        }

        private fun parseType(raw: String): CommentType {
            return when (raw.trim().uppercase()) {
                "COMMENT" -> CommentType.COMMENT
                "LIKE" -> CommentType.LIKE
                "RETWEET" -> CommentType.RETWEET
                else -> CommentType.COMMENT
            }
        }
    }
}
