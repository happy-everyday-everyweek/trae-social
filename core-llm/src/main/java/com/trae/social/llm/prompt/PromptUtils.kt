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
     * 提取规则：取第一个 '{' 到最后一个 '}' 之间的内容（含两端）。
     * 若响应中不存在 '{' 或 '}'，返回 null。
     */
    fun extractJson(rawText: String): String? {
        val start = rawText.indexOf('{')
        val end = rawText.lastIndexOf('}')
        if (start < 0 || end < 0 || end <= start) return null
        return rawText.substring(start, end + 1)
    }

    /**
     * 从可能包含 markdown 代码块或前后说明文字的响应中提取首个 JSON 数组片段。
     *
     * 优先按 '[' 到 ']' 提取；若响应中没有数组结构，回退到对象提取（兼容模型
     * 偶尔把数组包装成对象的情况）。
     */
    fun extractJsonArray(rawText: String): String? {
        val start = rawText.indexOf('[')
        val end = rawText.lastIndexOf(']')
        if (start in 0 until end) {
            return rawText.substring(start, end + 1)
        }
        // 兜底：模型可能用对象包裹数组，回退到对象提取。
        return extractJson(rawText)
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
