package com.trae.social.llm.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray

/**
 * Prompt 工程通用工具集。
 *
 * 提供对 LLM 返回文本的宽松 JSON 提取与解析能力，避免因模型偶尔输出
 * markdown 代码块或前后说明文字而导致解析失败（RISK-13）。
 */
object PromptUtils {

    /**
     * 从可能包含 markdown 代码块或前后说明文字的响应中提取首个 JSON 对象片段。
     *
     * 提取规则：从第一个 '{' 起，按配对括号匹配找到对应的闭合 '}'，
     * 取二者之间内容（含两端）。
     *
     * 与“首末括号取跨度”不同，配对匹配能正确处理 JSON 后附带含花括号的
     * 说明文字（LLM 常见行为），避免尾随说明中的 '}' 导致跨度越界、整体解析失败。
     * 字符串字面量内的括号不计入深度，避免字符串中的 '{' '}' 干扰匹配。
     *
     * 若响应中不存在 '{' 或找不到配对 '}'，返回 null。
     */
    fun extractJson(rawText: String): String? {
        val start = rawText.indexOf('{')
        if (start < 0) return null
        val end = findMatchingClose(rawText, start, '{', '}')
        if (end < 0) return null
        return rawText.substring(start, end + 1)
    }

    /**
     * 从可能包含 markdown 代码块或前后说明文字的响应中提取首个 JSON 数组片段。
     *
     * 优先按 '[' 起配对匹配 ']' 提取；若响应中没有数组结构或配对失败，
     * 回退到对象提取（兼容模型偶尔把数组包装成对象的情况）。
     */
    fun extractJsonArray(rawText: String): String? {
        val start = rawText.indexOf('[')
        if (start >= 0) {
            val end = findMatchingClose(rawText, start, '[', ']')
            if (end > start) {
                return rawText.substring(start, end + 1)
            }
        }
        // 兜底：模型可能用对象包裹数组，回退到对象提取。
        return extractJson(rawText)
    }

    /**
     * 从 [start] 位置的开放括号起，按配对深度匹配对应的闭合括号下标。
     *
     * - 跟踪字符串字面量（含反斜杠转义），字符串内的括号不影响深度。
     * - 仅统计与 [open]/[close] 相同的括号；其他类型括号不影响深度。
     * - 返回闭合括号下标；未找到配对返回 -1。
     */
    private fun findMatchingClose(
        text: String,
        start: Int,
        open: Char,
        close: Char,
    ): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /**
     * 安全地将 JSON 字符串解析为 [JsonObject]，解析失败返回 null。
     *
     * @param json 待解析的 JSON 字符串。
     * @param parser 使用的 [Json] 实例（应配置 ignoreUnknownKeys=true）。
     */
    fun safeParseJson(json: String, parser: Json): JsonObject? {
        return runCatching { parser.decodeFromString(JsonObject.serializer(), json) }.getOrNull()
    }

    /**
     * 安全地将 JSON 字符串解析为 [JsonArray]，解析失败返回 null。
     */
    fun safeParseJsonArray(json: String, parser: Json): JsonArray? {
        return runCatching { parser.decodeFromString(JsonArray.serializer(), json) }.getOrNull()
    }
}
