package com.trae.social.llm.prompt

import com.trae.social.core.data.TweetLimits
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.llm.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlin.random.Random

/**
 * 推文生成 Prompt 构建器（SubTask 5.1）。
 *
 * 将账号人设、当前时段、最近推文组装为 [ChatMessage] 列表（system + user），
 * 并提供对 LLM 返回 JSON 的宽松解析与推文后处理（错别字 / emoji / 截断）。
 *
 * 风险控制：
 * - RISK-2（人设漂移）：system prompt 强制注入全部人设固定字段。
 * - RISK-12（合规）：system prompt 含合规自检指令；#151 重构移除应用层 ContentFilter，
 *   敏感词检查下沉到模型层 / 上层审核流程。
 * - RISK-13（JSON 解析）：[parseTweetResult] 宽松解析，失败返回 null。
 */
class TweetPromptBuilder {

    /**
     * 账号人设输入（固定字段 + 当前情绪）。
     *
     * @param displayName 显示名。
     * @param profession 职业。
     * @param ageRange 年龄段，如 "25-34"。
     * @param culturalBackground 文化背景，如 "华东"。
     * @param worldview 世界观描述。
     * @param values 价值观描述。
     * @param languageStyle 语言风格，如 "口语"。
     * @param catchphrase 口癖列表或字符串。
     * @param emojiPreference 偏好的 emoji 字符序列（运行时由调用方传入）。
     * @param typoRate 错别字注入率，0.0-1.0。
     * @param recentMood 最近情绪状态。
     */
    data class PersonaInput(
        val displayName: String,
        val profession: String,
        val ageRange: String,
        val culturalBackground: String,
        val worldview: String,
        val values: String,
        val languageStyle: String,
        val catchphrase: String,
        val emojiPreference: List<String>,
        val typoRate: Double,
        val recentMood: String,
    ) {
        companion object {
            /**
             * #219：从 [AccountEntity] 构建 [PersonaInput] 的统一入口。
             *
             * 此前 InteractionWorker / PendingInteractionWorker / TweetGenerationWorker
             * 各自手动复制了相同的 11 字段映射（含 `catchphrase.joinToString("、")`
             * 与 `recentMood.ifBlank { "平和" }` 兑底细节），任一字段调整需改多处且易遗漏。
             * 抽到此 companion 后调用方统一使用 `PersonaInput.from(account)`。
             */
            fun from(account: AccountEntity): PersonaInput = PersonaInput(
                displayName = account.displayName,
                profession = account.profession,
                ageRange = account.ageRange,
                culturalBackground = account.culturalBackground,
                worldview = account.worldview,
                values = account.values,
                languageStyle = account.languageStyle,
                catchphrase = account.catchphrase.joinToString("、"),
                emojiPreference = account.emojiPreference,
                typoRate = account.typoRate,
                recentMood = account.recentMood.ifBlank { "平和" },
            )
        }
    }

    /**
     * 推文生成结果。
     *
     * @param text 推文正文，≤ 280 字符。
     * @param withImage 是否配图。
     * @param imageTheme 配图主题。
     * @param interactionTendency 互动倾向，0.0-1.0。
     */
    data class TweetGenerationResult(
        val text: String,
        val withImage: Boolean,
        val imageTheme: ImageTheme,
        val interactionTendency: Double,
    )

    /**
     * 配图主题枚举。
     */
    enum class ImageTheme { LANDSCAPE, FOOD, CITY, PET, SPORT, ART, TECH, NATURE, NONE }

    /**
     * 构建对话消息列表。
     *
     * @param persona 账号人设。
     * @param timeSlotDescription 当前时段描述，如 "工作日上午 09:00-12:00"。
     * @param recentTweets 最近 3 条该账号推文文本（用于避免重复）。
     * @return system + user 两条消息。
     */
    fun build(
        persona: PersonaInput,
        timeSlotDescription: String,
        recentTweets: List<String>,
    ): List<ChatMessage> {
        val system = buildSystemPrompt(persona)
        val user = buildUserPrompt(persona, timeSlotDescription, recentTweets)
        return listOf(
            ChatMessage(ChatMessage.Role.SYSTEM, system),
            ChatMessage(ChatMessage.Role.USER, user),
        )
    }

    private fun buildSystemPrompt(p: PersonaInput): String {
        // #304：人设字段来源为外部资产 / LLM 生成的动态字段，做字符级净化后再插值，
        // 降低换行伪造段落、零宽字符隐藏越狱指令等 prompt injection 风险。
        // #285：字段长度上限统一引用 PromptFieldLimits，避免与 CommentPromptBuilder 漂移
        val safeDisplayName = PromptUtils.sanitizeForPrompt(p.displayName, PromptFieldLimits.SINGLE_LINE)
        val safeProfession = PromptUtils.sanitizeForPrompt(p.profession, PromptFieldLimits.SINGLE_LINE)
        val safeAgeRange = PromptUtils.sanitizeForPrompt(p.ageRange, PromptFieldLimits.SHORT_TAG)
        val safeCultural = PromptUtils.sanitizeForPrompt(p.culturalBackground, PromptFieldLimits.SINGLE_LINE)
        val safeWorldview = PromptUtils.sanitizeForPrompt(p.worldview, PromptFieldLimits.LONG_PARAGRAPH)
        val safeValues = PromptUtils.sanitizeForPrompt(p.values, PromptFieldLimits.LONG_PARAGRAPH)
        val safeLanguageStyle = PromptUtils.sanitizeForPrompt(p.languageStyle, PromptFieldLimits.SINGLE_LINE)
        val safeCatchphrase = PromptUtils.sanitizeForPrompt(p.catchphrase, PromptFieldLimits.PARAGRAPH)
        val safeEmoji = if (p.emojiPreference.isEmpty()) "无"
            else p.emojiPreference.joinToString("/") { PromptUtils.sanitizeForPrompt(it, PromptFieldLimits.SHORT_TAG) }
        val safeMood = PromptUtils.sanitizeForPrompt(p.recentMood, PromptFieldLimits.SINGLE_LINE)
        return buildString {
            appendLine("你正在扮演一个真实存在的虚拟社交账号用户，请严格按以下人设发布内容。")
            appendLine(PromptUtils.DATA_NOT_INSTRUCTIONS_CLAUSE)
            appendLine("【人设固定字段】")
            appendLine("- 显示名：$safeDisplayName")
            appendLine("- 职业：$safeProfession")
            appendLine("- 年龄段：$safeAgeRange")
            appendLine("- 文化背景：$safeCultural")
            appendLine("- 世界观：$safeWorldview")
            appendLine("- 价值观：$safeValues")
            appendLine("- 语言风格：$safeLanguageStyle")
            appendLine("- 口癖：$safeCatchphrase")
            appendLine("- emoji 偏好：$safeEmoji")
            appendLine("- 错别字率：${p.typoRate}")
            appendLine("- 最近情绪：$safeMood")
            appendLine()
            appendLine("你是该人物，以第一人称发布一条原创推文。严格保持人设的语言风格与价值观。")
            appendLine("输出前检查内容不包含暴力、仇恨、色情或对真实人物的虚假陈述。")
        }
    }

    private fun buildUserPrompt(
        p: PersonaInput,
        timeSlotDescription: String,
        recentTweets: List<String>,
    ): String {
        // #304：推文历史为外部内容，净化后再插值
        val safeTimeSlot = PromptUtils.sanitizeForPrompt(timeSlotDescription, PromptFieldLimits.SINGLE_LINE)
        val safeMood = PromptUtils.sanitizeForPrompt(p.recentMood, PromptFieldLimits.SINGLE_LINE)
        return buildString {
            appendLine("【当前时段】")
            appendLine(safeTimeSlot)
            appendLine()
            appendLine("【最近情绪】")
            appendLine(safeMood)
            appendLine()
            appendLine("【最近 3 条该账号推文（避免重复）】")
            if (recentTweets.isEmpty()) {
                appendLine("（暂无历史推文）")
            } else {
                recentTweets.forEachIndexed { i, t ->
                    appendLine("${i + 1}. ${PromptUtils.sanitizeForPrompt(t, TweetLimits.MAX_TWEET_LENGTH)}")
                }
            }
            appendLine()
            appendLine("请输出 JSON：{\"text\": \"推文内容\", \"withImage\": true/false, \"imageTheme\": \"landscape/food/city/pet/sport/art/tech/nature/none\", \"interactionTendency\": 0.0-1.0}。")
            appendLine("约束：text 不超过 ${TweetLimits.MAX_TWEET_LENGTH} 字符；withImage 为布尔值；imageTheme 必须是上述枚举之一；interactionTendency 为 0.0-1.0 之间的浮点数。")
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
         * 宽松解析推文生成结果。
         *
         * 步骤：先提取 JSON 片段（兼容 markdown 代码块与前后说明文字），
         * 再按字段读取并对非法值降级。任一步骤失败返回 null。
         */
        fun parseTweetResult(rawText: String): TweetGenerationResult? {
            val jsonStr = PromptUtils.extractJson(rawText) ?: return null
            val obj = PromptUtils.safeParseJson(jsonStr, parser) ?: return null
            return parseFromObject(obj)
        }

        private fun parseFromObject(obj: JsonObject): TweetGenerationResult? {
            val text = obj["text"]?.let { field ->
                (field as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            } ?: return null

            val withImage = (obj["withImage"] as? JsonPrimitive)?.let {
                runCatching { it.boolean }.getOrDefault(false)
            } ?: false

            val imageTheme = (obj["imageTheme"] as? JsonPrimitive)?.content?.let { parseImageTheme(it) }
                ?: ImageTheme.NONE

            val tendency = (obj["interactionTendency"] as? JsonPrimitive)?.let {
                runCatching { it.double }.getOrDefault(0.5)
            } ?: 0.5
            val clamped = tendency.coerceIn(0.0, 1.0)

            return TweetGenerationResult(
                text = text,
                withImage = withImage,
                imageTheme = imageTheme,
                interactionTendency = clamped,
            )
        }

        private fun parseImageTheme(raw: String): ImageTheme {
            return when (raw.trim().lowercase()) {
                "landscape" -> ImageTheme.LANDSCAPE
                "food" -> ImageTheme.FOOD
                "city" -> ImageTheme.CITY
                "pet" -> ImageTheme.PET
                "sport" -> ImageTheme.SPORT
                "art" -> ImageTheme.ART
                "tech" -> ImageTheme.TECH
                "nature" -> ImageTheme.NATURE
                "none" -> ImageTheme.NONE
                else -> ImageTheme.NONE
            }
        }
    }
}

/**
 * 推文后处理器。
 *
 * 在 LLM 返回文本之后、写入数据库之前执行：
 * - 按人设 [TweetPromptBuilder.PersonaInput.typoRate] 随机注入错别字；
 * - 按人设 emoji 偏好末尾随机追加 1-2 个 emoji；
 * - 超长文本截断并加省略号。
 *
 * 注意：emoji 字符由调用方通过 [emojis] 参数传入，本类不在源码中硬编码任何 emoji。
 */
class TweetPostProcessor {

    /**
     * 常见错别字替换表（双向可互换）。
     *
     * key 为原字符，value 为可替换的候选字符列表。
     */
    private val typoTable: Map<Char, List<Char>> = mapOf(
        '的' to listOf('得', '地'),
        '得' to listOf('的', '地'),
        '地' to listOf('的', '得'),
        '在' to listOf('再'),
        '再' to listOf('在'),
        '做' to listOf('作'),
        '作' to listOf('做'),
    )

    /**
     * 按 [typoRate] 随机将文本中的可替换字符替换为错别字。
     *
     * 当 [typoRate] >= 1.0 时，所有可替换字符必定被替换（便于测试）。
     * 当 [typoRate] <= 0.0 时，原文本不变。
     *
     * @param text 原文本。
     * @param typoRate 错别字注入率，0.0-1.0。
     * @param random 随机源。
     */
    fun applyTypos(text: String, typoRate: Double, random: Random): String {
        if (typoRate <= 0.0 || text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val candidates = typoTable[ch]
            if (candidates != null && candidates.isNotEmpty() && shouldReplace(typoRate, random)) {
                sb.append(candidates.random(random))
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun shouldReplace(typoRate: Double, random: Random): Boolean {
        // typoRate=1.0 时 nextDouble() 永远 < 1.0，确保必定替换。
        return random.nextDouble() < typoRate
    }

    /**
     * 在文本末尾随机追加 1-2 个 emoji。
     *
     * @param text 原文本。
     * @param emojis 候选 emoji 字符串列表（由人设 emojiPreference 提供）。
     * @param random 随机源。
     * @return 追加 emoji 后的文本；[emojis] 为空时返回原文本。
     */
    fun appendEmojis(text: String, emojis: List<String>, random: Random): String {
        if (emojis.isEmpty()) return text
        val count = if (random.nextBoolean()) 1 else 2
        val picked = mutableListOf<String>()
        repeat(count) {
            picked.add(emojis.random(random))
        }
        return text + picked.joinToString("")
    }

    /**
     * 超长文本截断并追加省略号。
     *
     * 若 [text].length > [max]，截取前 (max - 1) 个字符并追加 "…"，
     * 保证最终长度不超过 [max]。
     *
     * 截断位置若落在 UTF-16 代理对（surrogate pair，如 emoji）中间，会产生
     * 孤立高位/低位代理项，是非法 Unicode，写入 Room/SQLite 会变为替换字符
     * 或触发编码异常。此处将截断点回退至代理对边界之前，避免产生孤立代理项。
     *
     * @param text 原文本。
     * @param max 最大字符数，默认 280。
     */
    fun truncate(text: String, max: Int = TweetLimits.MAX_TWEET_LENGTH): String {
        if (text.length <= max) return text
        var cut = if (max <= 1) 0 else max - 1
        // 若 cut 恰好落在代理对的高位代理之后、低位代理之前，
        // text[cut - 1] 为高位代理，take(cut) 会留下孤立高位代理。
        // 向前回退直到不以高位代理结尾，确保不破坏代理对完整性。
        while (cut > 0 && text[cut - 1].isHighSurrogate()) {
            cut--
        }
        return text.take(cut) + "…"
    }
}
