package com.trae.social.llm.anthropic

import com.trae.social.llm.ChatConfig
import com.trae.social.llm.ChatMessage
import com.trae.social.llm.LlmClient
import com.trae.social.llm.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Anthropic 客户端实现。
 *
 * SSE 协议：每事件由 `event: <type>\n` 行标记类型，`data: {json}\n` 行携带 payload，
 * 事件间以空行分隔。增量文本仅在 `content_block_delta` 事件的 `delta.text` 中。
 *
 * Anthropic 不支持原生 JSON mode，[ChatConfig.jsonMode] 时通过在 system prompt
 * 中追加约束指令实现（RISK-13）。
 *
 * 流式失败时（且尚未 emit 任何 token），自动降级为 [chatSync]（RISK-4）。
 */
class AnthropicClient(
    private val api: AnthropicApi,
    private val model: String,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : LlmClient {

    override val provider: LlmProvider = LlmProvider.ANTHROPIC

    override suspend fun chat(messages: List<ChatMessage>, config: ChatConfig): Flow<String> = flow {
        var emitted = false
        try {
            val body = api.streamChat(buildRequest(messages, config, stream = true))
            body.use { responseBody ->
                val source = responseBody.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith(DATA_PREFIX)) continue
                    val payload = line.removePrefix(DATA_PREFIX).trim()
                    if (payload.isEmpty()) continue
                    val event = runCatching {
                        json.decodeFromString<AnthropicStreamEvent>(payload)
                    }.getOrNull() ?: continue
                    if (event.type == EVENT_MESSAGE_STOP) break
                    if (event.type != EVENT_CONTENT_BLOCK_DELTA) continue
                    val token = event.delta?.text
                    if (!token.isNullOrEmpty()) {
                        emit(token)
                        emitted = true
                    }
                }
            }
        } catch (e: Exception) {
            if (!emitted) {
                val full = runCatching { chatSync(messages, config) }.getOrDefault("")
                if (full.isNotEmpty()) emit(full)
            }
        }
    }

    override suspend fun chatSync(messages: List<ChatMessage>, config: ChatConfig): String {
        val response = api.chat(buildRequest(messages, config, stream = false))
        return response.content.firstOrNull { it.type == TYPE_TEXT }?.text.orEmpty()
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

    private fun buildRequest(
        messages: List<ChatMessage>,
        config: ChatConfig,
        stream: Boolean,
    ): AnthropicRequest {
        val system = messages.filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n") { it.content }
            .let { if (it.isBlank()) null else it }
            .let { base ->
                if (config.jsonMode && base != null) "$base\n\n$JSON_MODE_HINT"
                else if (config.jsonMode) JSON_MODE_HINT
                else base
            }
        val conversation = messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .map { it.toAnthropic() }
        return AnthropicRequest(
            model = model,
            maxTokens = config.maxTokens,
            system = system,
            messages = conversation,
            temperature = config.temperature,
            stream = stream,
        )
    }

    private fun ChatMessage.toAnthropic(): AnthropicMessage = AnthropicMessage(
        role = when (role) {
            ChatMessage.Role.USER -> "user"
            ChatMessage.Role.ASSISTANT -> "assistant"
            ChatMessage.Role.SYSTEM -> "user"
        },
        content = content,
    )

    private companion object {
        const val DATA_PREFIX = "data:"
        const val EVENT_CONTENT_BLOCK_DELTA = "content_block_delta"
        const val EVENT_MESSAGE_STOP = "message_stop"
        const val TYPE_TEXT = "text"
        const val JSON_MODE_HINT =
            "请严格只输出合法 JSON 对象，不要包含 markdown 代码块标记或额外说明。"
    }
}
