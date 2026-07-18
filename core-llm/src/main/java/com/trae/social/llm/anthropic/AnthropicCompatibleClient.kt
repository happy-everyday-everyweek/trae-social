package com.trae.social.llm.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.http.StreamResponse
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.RawMessageStreamEvent
import com.trae.social.core.data.config.ModelCapability
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.ChatMessage
import com.trae.social.llm.ContentPart
import com.trae.social.llm.EndpointConfig
import com.trae.social.llm.LlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.IOException

/**
 * Anthropic 兼容协议的客户端实现（#151 重构）。
 *
 * 用官方 [AnthropicOkHttpClient] SDK 取代旧手写 Retrofit + SSE 解析。
 *
 * - **自动重试**：SDK 内置 429 / 5xx 指数退避重试（`.maxRetries(2)`），
 *   旧 [com.trae.social.llm.interceptor.RetryInterceptor] 不再生效。
 * - **JSON mode**：Anthropic 不支持原生 `response_format`，由 [DefaultRulesetEngine]
 *   在 system prompt 中追加约束指令实现（本类不感知 jsonMode）。
 * - **多模态**：Anthropic 不接受图像 URL，需 base64 编码。本类仅处理纯文本，
 *   含 [ContentPart.Image] 的消息由引擎层先做 URL→base64 转换或文本描述降级；
 *   若本类直接收到 multimodal 消息，仅取首个文本块以保证不崩。
 *
 * 流式失败降级策略与 [OpenAiCompatibleClient] 一致：
 * - 已 emit 部分 token 后中断 → 抛 [IOException]（内容不完整）
 * - 尚未 emit 时遭遇非持久性错误 → 降级为 [chatSync]
 * - 持久性 HTTP 4xx（非 429）→ 直接 rethrow
 *
 * 注：Anthropic Java SDK 2.34.1 关键 API 形态——
 * - `AnthropicOkHttpClient.builder().apiKey().baseUrl().maxRetries().build()` 返回
 *   [AnthropicClient]（接口类型）。
 * - `client.messages().createStreaming(params)` 返回
 *   [StreamResponse]<[RawMessageStreamEvent]>；`.stream()` 返回
 *   `java.util.stream.Stream<RawMessageStreamEvent>`。
 * - [RawMessageStreamEvent] 是 union，本身没有 `.delta()` 或 `.error()` 方法，
 *   必须先 `isContentBlockDelta()` 判断，再 `asContentBlockDelta().delta()` 拿
 *   `RawContentBlockDelta`；它的方法名是 `isText()` / `asText()`，
 *   **不是** `isTextDelta()` / `asTextDelta()`。错误通过异常抛出，不走事件。
 * - [MessageParam] 有 builder，但 `Role` 只有 USER / ASSISTANT，SYSTEM 消息走
 *   [MessageCreateParams.builder]`.system(String)`。
 * - [Message.content()] 返回 `List<ContentBlock>`（不是 String），
 *   要遍历取 `TextBlock.asText().text()`。
 */
class AnthropicCompatibleClient(
    private val endpoint: EndpointConfig,
) : LlmClient {

    override val endpointId: String = endpoint.id
    override val capabilities: Set<ModelCapability> = endpoint.capabilities

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apply {
            endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { apiKey(it) }
            baseUrl(endpoint.baseUrl)
            // SDK 内置 429/5xx 重试，最多 2 次（含首次共 3 次尝试），与旧 RetryInterceptor 等价
            maxRetries(2)
        }
        .build()

    override suspend fun chat(messages: List<ChatMessage>, config: ChatConfig): Flow<String> = flow {
        var emitted = false
        try {
            val params = buildParams(messages, config)
            client.messages().createStreaming(params).use { streamResponse: StreamResponse<RawMessageStreamEvent> ->
                // 用 iterator 显式迭代 java.util.stream.Stream，才能在 suspend 上下文 emit
                streamResponse.stream().use { stream ->
                    val iterator = stream.iterator()
                    while (iterator.hasNext()) {
                        val event = iterator.next()
                        // RawMessageStreamEvent 是 union，本身没有 .delta() 方法
                        if (!event.isContentBlockDelta) continue
                        val blockDelta = event.asContentBlockDelta().delta()
                        // delta 的方法名是 isText / asText，不是 isTextDelta / asTextDelta
                        if (!blockDelta.isText) continue
                        val text = blockDelta.asText().text()
                        if (text.isNotEmpty()) {
                            emit(text)
                            emitted = true
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (emitted) {
                throw IOException("streaming truncated after partial emit", e)
            }
            if (isPersistentHttpError(e)) {
                Timber.w(e, "Anthropic 流式 chat 遭遇持久性 HTTP 错误，不降级")
                throw e
            }
            try {
                val full = chatSync(messages, config)
                if (full.isNotEmpty()) emit(full)
            } catch (fallbackError: Exception) {
                Timber.w(fallbackError, "降级 chatSync 也失败")
                throw fallbackError
            }
        }
    }

    override suspend fun chatSync(messages: List<ChatMessage>, config: ChatConfig): String {
        val params = buildParams(messages, config)
        val response: Message = client.messages().create(params)
        // Message.content() 返回 List<ContentBlock>，要遍历取 TextBlock.text()
        return response.content()
            .filter { it.isText }
            .joinToString("") { it.asText().text() }
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

    /**
     * 构造 SDK 请求参数。
     *
     * Anthropic API 把 system 与对话消息分离：SYSTEM 角色消息合并后通过 `.system(String)` 设置，
     * USER/ASSISTANT 走 `.messages(List<MessageParam>)`。
     *
     * JSON mode 不在此处处理（Anthropic 无原生 response_format）；
     * 由 [DefaultRulesetEngine] 在 system prompt 追加约束指令。
     */
    private fun buildParams(
        messages: List<ChatMessage>,
        config: ChatConfig,
    ): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(endpoint.model)
            .maxTokens(config.maxTokens.toLong())
            .temperature(config.temperature.toDouble())

        val systemText = messages
            .filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n") { it.textContent() }
            .takeIf { it.isNotBlank() }
        if (systemText != null) {
            builder.system(systemText)
        }

        val conversation = messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .map { it.toAnthropicMessageParam() }
        builder.messages(conversation)

        return builder.build()
    }

    /**
     * 把 [ChatMessage] 映射为 SDK 消息参数。
     *
     * 当前实现仅处理纯文本（Anthropic 多模态需 base64 编码图像，
     * 由引擎层提前预处理为文本描述，不在此处展开）。
     * MessageParam.Role 只有 USER / ASSISTANT（SYSTEM 在 buildParams 中已过滤，
     * 单独通过 MessageCreateParams.system 设置）。
     */
    private fun ChatMessage.toAnthropicMessageParam(): MessageParam {
        val role = when (role) {
            ChatMessage.Role.USER -> MessageParam.Role.USER
            ChatMessage.Role.ASSISTANT -> MessageParam.Role.ASSISTANT
            ChatMessage.Role.SYSTEM -> error("SYSTEM 消息应通过 MessageCreateParams.system 设置，不应走到 toAnthropicMessageParam")
        }
        val text = textContent()
        return MessageParam.builder()
            .role(role)
            .content(text)
            .build()
    }

    private fun isPersistentHttpError(e: Throwable): Boolean {
        val className = e::class.qualifiedName ?: e::class.simpleName.orEmpty()
        if (!className.contains("Anthropic")) return false
        val code = extractHttpCode(e.message.orEmpty())
        if (code != null) {
            return code in 400..499 && code != 429
        }
        val regex = Regex("""\b4\d{2}\b""")
        val match = regex.find(className) ?: regex.find(e.message.orEmpty())
        return match != null && match.value != "429"
    }

    private fun extractHttpCode(message: String): Int? {
        val regex = Regex("""\b(4\d{2}|5\d{2})\b""")
        return regex.find(message)?.value?.toIntOrNull()
    }
}
