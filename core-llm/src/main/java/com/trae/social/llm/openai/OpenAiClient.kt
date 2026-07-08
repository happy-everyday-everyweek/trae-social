package com.trae.social.llm.openai

import com.trae.social.llm.ChatConfig
import com.trae.social.llm.ChatMessage
import com.trae.social.llm.LlmClient
import com.trae.social.core.data.config.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * OpenAI（及兼容 OpenAI 协议的自定义端点）客户端实现。
 *
 * SSE 协议：每个事件以 `data: {json}\n` 行携带 payload，事件间以空行分隔；
 * 流终止标记为 `data: [DONE]`。增量文本位于 `choices[0].delta.content`。
 *
 * 流式失败时（且尚未 emit 任何 token），自动降级为 [chatSync] 后单条 emit（RISK-4）。
 *
 * P1 修复：[provider] 由构造参数注入，使 CUSTOM 端点能正确报告自身 provider，
 * 并在每次请求中动态注入 provider 头供 AuthInterceptor 读取对应 API Key。
 */
class OpenAiClient(
    private val api: OpenAiApi,
    private val model: String,
    override val provider: LlmProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : LlmClient {

    override suspend fun chat(messages: List<ChatMessage>, config: ChatConfig): Flow<String> = flow {
        var emitted = false
        try {
            val body = api.streamChat(buildRequest(messages, config, stream = true), provider.id)
            body.use { responseBody ->
                val source = responseBody.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith(DATA_PREFIX)) continue
                    val payload = line.removePrefix(DATA_PREFIX).trim()
                    if (payload.isEmpty()) continue
                    if (payload == DONE_MARKER) break
                    val token = parseDelta(payload) ?: continue
                    if (token.isNotEmpty()) {
                        emit(token)
                        emitted = true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (emitted) {
                // IMPL-8：已 emit 部分 token 后中断，抛异常通知调用方内容不完整，
                // 避免写入残缺推文
                throw IOException("streaming truncated after partial emit", e)
            }
            // 尚未 emit：降级为非流式调用
            val full = runCatching { chatSync(messages, config) }.getOrDefault("")
            if (full.isNotEmpty()) emit(full)
        }
    }

    override suspend fun chatSync(messages: List<ChatMessage>, config: ChatConfig): String {
        val response = api.chat(buildRequest(messages, config, stream = false), provider.id)
        return response.choices.firstOrNull()?.message?.content.orEmpty()
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
    ): OpenAiRequest {
        val mapped = messages.map { it.toOpenAi() }
        val format = if (config.jsonMode) OpenAiResponseFormat() else null
        return OpenAiRequest(
            model = model,
            messages = mapped,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            stream = stream,
            responseFormat = format,
        )
    }

    private fun parseDelta(payload: String): String? {
        val chunk = runCatching { json.decodeFromString<OpenAiResponse>(payload) }.getOrNull()
        return chunk?.choices?.firstOrNull()?.delta?.content
    }

    private fun ChatMessage.toOpenAi(): OpenAiMessage = OpenAiMessage(
        role = when (role) {
            ChatMessage.Role.SYSTEM -> "system"
            ChatMessage.Role.USER -> "user"
            ChatMessage.Role.ASSISTANT -> "assistant"
        },
        content = content,
    )

    private companion object {
        const val DATA_PREFIX = "data:"
        const val DONE_MARKER = "[DONE]"
    }
}
