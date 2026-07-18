package com.trae.social.llm.openai

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completion.ChatCompletion
import com.openai.models.chat.completion.ChatCompletionChunk
import com.openai.models.chat.completion.ChatCompletionContentPart
import com.openai.models.chat.completion.ChatCompletionCreateParams
import com.openai.models.chat.completion.ChatCompletionMessageParam
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
 * OpenAI 兼容协议的客户端实现（#151 重构）。
 *
 * 用官方 [OpenAIOkHttpClient] SDK 取代旧手写 Retrofit + SSE 解析，统一覆盖：
 * - OpenAI 官方端点
 * - 兼容 OpenAI 协议的第三方端点（Deepseek / Moonshot / 智谱 / SiliconFlow / 本地 Ollama）
 * - Google Gemini 官方 OpenAI 兼容端点（`/v1beta/openai/`）
 *
 * **流式失败降级**：尚未 emit 任一 token 时遭遇非持久性错误（5xx / IO），
 * 自动降级为 [chatSync] 一次性 emit；已 emit 后中断则抛 [IOException] 通知调用方
 * 内容不完整。持久性 HTTP 4xx（401 / 403 / 400 等）不降级，直接 rethrow。
 *
 * **JSON mode**：端点声明 [ModelCapability.JSON_MODE_NATIVE] 时通过 SDK
 * `response_format = json_object` 原生请求；否则由 [DefaultRulesetEngine] 在 system prompt
 * 中追加约束指令做 prompt 降级（在引擎层处理，本类不感知）。
 *
 * **多模态**：当消息含 [ContentPart.Image] 块时，构造 OpenAI 多模态 content 数组
 * （`image_url` 形式）发送；纯文本走 SDK 便捷方法。
 *
 * 鉴权头 `Authorization: Bearer <key>` 由 SDK 内部注入，不再需要 AuthInterceptor。
 */
class OpenAiCompatibleClient(
    private val endpoint: EndpointConfig,
) : LlmClient {

    override val endpointId: String = endpoint.id
    override val capabilities: Set<ModelCapability> = endpoint.capabilities

    private val client: OpenAIOkHttpClient = OpenAIOkHttpClient.builder()
        .apply {
            endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { apiKey(it) }
            // baseUrl 已包含版本路径（如 "https://api.openai.com/v1/"）
            baseUrl(endpoint.baseUrl)
        }
        .build()

    override suspend fun chat(messages: List<ChatMessage>, config: ChatConfig): Flow<String> = flow {
        var emitted = false
        try {
            val params = buildParams(messages, config)
            client.chat().completions().createStreaming(params).use { streamResponse: StreamResponse<ChatCompletionChunk> ->
                streamResponse.stream().forEach { chunk ->
                    chunk.choices().forEach { choice ->
                        val delta = choice.delta()
                        val token = delta.content().orElse(null)
                        if (!token.isNullOrEmpty()) {
                            emit(token)
                            emitted = true
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (emitted) {
                // 已 emit 部分 token 后中断，抛异常通知调用方内容不完整
                throw IOException("streaming truncated after partial emit", e)
            }
            if (isPersistentHttpError(e)) {
                Timber.w(e, "OpenAI 流式 chat 遭遇持久性 HTTP 错误，不降级")
                throw e
            }
            // 尚未 emit：降级为非流式调用
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
        val completion: ChatCompletion = client.chat().completions().create(params)
        val choice = completion.choices().firstOrNull()
            ?: run {
                Timber.w("chatSync 返回空 choices，可能被安全策略拦截或服务异常")
                return ""
            }
        if (choice.finishReason().isPresent) {
            val reason = choice.finishReason().get()
            if (reason.toString().equals("content_filter", ignoreCase = true)) {
                Timber.w("chatSync 响应被内容安全策略拦截 (finish_reason=%s)", reason)
            }
        }
        return choice.message().content().orElse("")
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
     * 构造 SDK 请求参数。多模态消息用 content 数组形式，纯文本走便捷字符串形式。
     */
    private fun buildParams(
        messages: List<ChatMessage>,
        config: ChatConfig,
    ): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
            .model(endpoint.model)
            .maxTokens(config.maxTokens.toLong())
            .temperature(config.temperature.toDouble())

        // JSON mode 原生支持（端点声明 JSON_MODE_NATIVE 才用原生方式）
        if (config.jsonMode && ModelCapability.JSON_MODE_NATIVE in endpoint.capabilities) {
            builder.responseFormat(
                ChatCompletionCreateParams.ResponseFormat.builder()
                    .type(ChatCompletionCreateParams.ResponseFormat.Type.JSON_OBJECT)
                    .build()
            )
        }

        val sdkMessages = messages.map { it.toOpenAiMessageParam() }
        builder.messages(sdkMessages)
        return builder.build()
    }

    /**
     * 把 [ChatMessage] 映射为 SDK 消息参数。
     *
     * - 全文本：用 String content（兼容性最好，SDK 自动选择最简形式）
     * - 含多模态：构造 [ChatCompletionContentPart] 数组（仅支持 Text + Image）
     */
    private fun ChatMessage.toOpenAiMessageParam(): ChatCompletionMessageParam {
        val hasMultimodal = content.any { it !is ContentPart.Text }
        val role = when (role) {
            ChatMessage.Role.SYSTEM -> ChatCompletionMessageParam.SystemMessageParam.Role.SYSTEM
            ChatMessage.Role.USER -> ChatCompletionMessageParam.UserMessageParam.Role.USER
            ChatMessage.Role.ASSISTANT -> ChatCompletionMessageParam.AssistantMessageParam.Role.ASSISTANT
        }
        return when {
            !hasMultimodal -> {
                val text = content.firstNotNullOfOrNull { (it as? ContentPart.Text)?.text } ?: ""
                when (role) {
                    ChatCompletionMessageParam.SystemMessageParam.Role.SYSTEM ->
                        ChatCompletionMessageParam.builder().ofSystemMessage(
                            ChatCompletionMessageParam.SystemMessageParam.builder()
                                .role(role)
                                .content(text)
                                .build()
                        ).build()

                    ChatCompletionMessageParam.UserMessageParam.Role.USER ->
                        ChatCompletionMessageParam.builder().ofUserMessage(
                            ChatCompletionMessageParam.UserMessageParam.builder()
                                .role(role)
                                .content(text)
                                .build()
                        ).build()

                    ChatCompletionMessageParam.AssistantMessageParam.Role.ASSISTANT ->
                        ChatCompletionMessageParam.builder().ofAssistantMessage(
                            ChatCompletionMessageParam.AssistantMessageParam.builder()
                                .role(role)
                                .content(text)
                                .build()
                        ).build()
                }
            }
            else -> {
                // 多模态：仅 USER 角色支持 multimodal（SYSTEM/ASSISTANT 取首个文本）
                if (role == ChatCompletionMessageParam.UserMessageParam.Role.USER) {
                    val parts = content.map { part ->
                        when (part) {
                            is ContentPart.Text -> ChatCompletionMessageParam.UserMessageParam.Content.ofTextPart(
                                ChatCompletionContentPart.Text.builder().text(part.text).build()
                            )
                            is ContentPart.Image -> ChatCompletionMessageParam.UserMessageParam.Content.ofImagePart(
                                ChatCompletionContentPart.Image.builder()
                                    .imageUrl(
                                        ChatCompletionContentPart.Image.ImageURL.builder()
                                            .url(part.url)
                                            .build()
                                    )
                                    .build()
                            )
                            is ContentPart.Audio, is ContentPart.Video -> {
                                // OpenAI Chat Completions 当前不直接支持 audio/video URL 输入；
                                // 多模态降级链里应在调用前转换为文本描述。
                                ChatCompletionMessageParam.UserMessageParam.Content.ofTextPart(
                                    ChatCompletionContentPart.Text.builder()
                                        .text("[unsupported media type: ${part::class.simpleName}]")
                                        .build()
                                )
                            }
                        }
                    }
                    ChatCompletionMessageParam.builder().ofUserMessage(
                        ChatCompletionMessageParam.UserMessageParam.builder()
                            .role(role)
                            .content(parts)
                            .build()
                    ).build()
                } else {
                    val text = content.firstNotNullOfOrNull { (it as? ContentPart.Text)?.text } ?: ""
                    when (role) {
                        ChatCompletionMessageParam.SystemMessageParam.Role.SYSTEM ->
                            ChatCompletionMessageParam.builder().ofSystemMessage(
                                ChatCompletionMessageParam.SystemMessageParam.builder()
                                    .role(role).content(text).build()
                            ).build()

                        ChatCompletionMessageParam.AssistantMessageParam.Role.ASSISTANT ->
                            ChatCompletionMessageParam.builder().ofAssistantMessage(
                                ChatCompletionMessageParam.AssistantMessageParam.builder()
                                    .role(role).content(text).build()
                            ).build()

                        else -> error("unreachable")
                    }
                }
            }
        }
    }

    /**
     * 判断异常是否为持久性 HTTP 错误（4xx 非 429）。
     *
     * OpenAI SDK 的 4xx 异常继承自 [com.openai.errors.OpenAIError]，统一通过类名识别，
     * 避免本模块直接依赖 errors 子包（保持二进制兼容空间）。
     */
    private fun isPersistentHttpError(e: Throwable): Boolean {
        val className = e::class.qualifiedName ?: e::class.simpleName.orEmpty()
        if (!className.contains("OpenAI")) return false
        // 类名形如 OpenAIService4xxError / OpenAIBadRequestException / ...429
        val code = extractHttpCode(e.message.orEmpty())
        if (code != null) {
            return code in 400..499 && code != 429
        }
        // 类名直接含 4xx 后缀（如 OpenAIService400Error）
        val regex = Regex("""\b4\d{2}\b""")
        val match = regex.find(className)
        return match != null && match.value != "429"
    }

    private fun extractHttpCode(message: String): Int? {
        val regex = Regex("""\b(4\d{2}|5\d{2})\b""")
        return regex.find(message)?.value?.toIntOrNull()
    }
}
