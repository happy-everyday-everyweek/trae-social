package com.trae.social.llm.gemini

import com.trae.social.llm.ChatConfig
import com.trae.social.llm.ChatMessage
import com.trae.social.llm.LlmClient
import com.trae.social.llm.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Google Gemini 客户端实现。
 *
 * 流式响应为 HTTP chunked（非 SSE），整体为 JSON 数组 `[{...}, {...}]`，
 * 各元素随生成增量到达。通过花括号深度计数逐元素解析并 emit，
 * 实现真正的流式输出。
 *
 * 增量文本位于 `candidates[0].content.parts[0].text`。
 *
 * JSON mode（RISK-13）：Gemini 1.5+ 通过 `generationConfig.responseMimeType`
 * = "application/json" 原生支持，无需 prompt 约束。
 *
 * 流式失败时（且尚未 emit 任何 token），自动降级为 [chatSync]（RISK-4）。
 */
class GeminiClient(
    private val api: GeminiApi,
    private val model: String,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : LlmClient {

    override val provider: LlmProvider = LlmProvider.GEMINI

    override suspend fun chat(messages: List<ChatMessage>, config: ChatConfig): Flow<String> = flow {
        var emitted = false
        try {
            val body = api.streamChat(model, buildRequest(messages, config))
            body.use { responseBody ->
                responseBody.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    val parser = StreamingJsonArrayParser { objText ->
                        val resp = runCatching {
                            json.decodeFromString<GeminiResponse>(objText)
                        }.getOrNull() ?: return@StreamingJsonArrayParser
                        val text = resp.candidates.firstOrNull()
                            ?.content?.parts?.firstOrNull()?.text
                        if (!text.isNullOrEmpty()) {
                            emit(text)
                            emitted = true
                        }
                    }
                    val charBuffer = CharArray(CHUNK_SIZE)
                    while (true) {
                        val read = reader.read(charBuffer)
                        if (read <= 0) break
                        parser.feed(charBuffer, 0, read)
                    }
                    parser.finish()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (emitted) {
                // IMPL-8：已 emit 部分 token 后中断，抛异常通知调用方内容不完整
                throw IOException("streaming truncated after partial emit", e)
            }
            val full = runCatching { chatSync(messages, config) }.getOrDefault("")
            if (full.isNotEmpty()) emit(full)
        }
    }

    override suspend fun chatSync(messages: List<ChatMessage>, config: ChatConfig): String {
        val response = api.chat(model, buildRequest(messages, config))
        return response.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text.orEmpty()
    }

    override suspend fun ping(): Boolean {
        val result = runCatching {
            chatSync(
                listOf(ChatMessage(ChatMessage.Role.USER, "ping")),
                ChatConfig(temperature = 0.0f, maxTokens = 8),
            )
        }
        return result.getOrDefault("").isNotBlank()
    }

    private fun buildRequest(messages: List<ChatMessage>, config: ChatConfig): GeminiRequest {
        val system = messages.filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n") { it.content }
            .let { if (it.isBlank()) null else it }
            ?.let { GeminiContent(parts = listOf(GeminiPart(text = it))) }

        val contents = messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .map { it.toGemini() }

        val generationConfig = GeminiGenerationConfig(
            temperature = config.temperature,
            maxOutputTokens = config.maxTokens,
            responseMimeType = if (config.jsonMode) "application/json" else null,
        )
        return GeminiRequest(
            contents = contents,
            systemInstruction = system,
            generationConfig = generationConfig,
        )
    }

    private fun ChatMessage.toGemini(): GeminiContent = GeminiContent(
        role = when (role) {
            ChatMessage.Role.USER -> "user"
            ChatMessage.Role.ASSISTANT -> "model"
            ChatMessage.Role.SYSTEM -> "user"
        },
        parts = listOf(GeminiPart(text = content)),
    )

    /**
     * 增量解析 JSON 数组的辅助类。
     *
     * 输入为字符流（可能跨多次 [feed] 调用），输出为一个个完整的顶层 JSON 对象字符串
     * （通过 [onObject] 回调）。支持字符串内的转义字符与嵌套对象。
     */
    private class StreamingJsonArrayParser(private val onObject: suspend (String) -> Unit) {
        private val buffer = StringBuilder()
        private var depth = 0
        private var inString = false
        private var escape = false

        suspend fun feed(chars: CharArray, offset: Int, length: Int) {
            for (i in offset until offset + length) {
                val c = chars[i]
                if (escape) {
                    escape = false
                    if (depth > 0) buffer.append(c)
                    continue
                }
                if (inString) {
                    if (c == BACKSLASH) {
                        escape = true
                        if (depth > 0) buffer.append(c)
                        continue
                    }
                    if (c == QUOTE) {
                        inString = false
                    }
                    if (depth > 0) buffer.append(c)
                    continue
                }
                when (c) {
                    QUOTE -> {
                        inString = true
                        if (depth > 0) buffer.append(c)
                    }
                    OPEN_BRACE -> {
                        depth++
                        if (depth == 1) {
                            buffer.clear()
                        }
                        buffer.append(c)
                    }
                    CLOSE_BRACE -> {
                        buffer.append(c)
                        depth--
                        if (depth == 0) {
                            onObject(buffer.toString())
                            buffer.clear()
                        }
                    }
                    else -> {
                        if (depth > 0) buffer.append(c)
                    }
                }
            }
        }

        fun finish() {
            if (depth != 0) {
                throw IOException("unterminated JSON object in stream")
            }
        }

        private companion object {
            const val QUOTE = '"'
            const val BACKSLASH = '\\'
            const val OPEN_BRACE = '{'
            const val CLOSE_BRACE = '}'
        }
    }

    private companion object {
        const val CHUNK_SIZE = 8192
    }
}
