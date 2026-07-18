package com.trae.social.llm.openai

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.http.StreamResponse
import com.openai.models.ResponseFormatJsonObject
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
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
 *
 * 注：OpenAI Java SDK 4.43.0 关键 API 形态——
 * - `OpenAIOkHttpClient.builder().apiKey().baseUrl().build()` 返回 [OpenAIClient]（接口类型）。
 * - `client.chat().completions().createStreaming(params)` 返回
 *   [StreamResponse]<[ChatCompletionChunk]>；`.stream()` 返回
 *   `java.util.stream.Stream<ChatCompletionChunk>`，需要在 suspend 上下文用 iterator
 *   显式迭代才能 emit token。
 * - `ChatCompletionMessageParam` 是 union 类型，无 builder，用 `ofSystem/ofUser/ofAssistant`
 *   工厂包装具体角色 message param。
 * - 多模态 content 用 [ChatCompletionContentPart] union + 顶层 [ChatCompletionContentPartText]
 *   / [ChatCompletionContentPartImage] 类构造。
 */
class OpenAiCompatibleClient(
    private val endpoint: EndpointConfig,
) : LlmClient {

    override val endpointId: String = endpoint.id
    override val capabilities: Set<ModelCapability> = endpoint.capabilities

    private val client: OpenAIClient = OpenAIOkHttpClient.builder()
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
                // 用 iterator 显式迭代 java.util.stream.Stream，才能在 inline lambda 之外
                // emit suspend 调用（Java Stream 的 forEach 接受非 suspend 的 Consumer，
                // 直接在里面 emit 会编译错）。
                streamResponse.stream().use { stream ->
                    val iterator = stream.iterator()
                    while (iterator.hasNext()) {
                        val chunk = iterator.next()
                        for (choice in chunk.choices()) {
                            val token = choice.delta().content().orElse(null)
                            if (!token.isNullOrEmpty()) {
                                emit(token)
                                emitted = true
                            }
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
        // ChatCompletion.Choice.finishReason() 非 Optional，直接拿
        val reason = choice.finishReason()
        if (reason != null && reason.toString().equals("content_filter", ignoreCase = true)) {
            Timber.w("chatSync 响应被内容安全策略拦截 (finish_reason=%s)", reason)
        }
        // ChatCompletionMessage.content() 返回 Optional<String>
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
            builder.responseFormat(ResponseFormatJsonObject.builder().build())
        }

        // 用 addMessage(ChatCompletionMessageParam) 逐条添加，每条消息单独构造 union 实例
        for (msg in messages) {
            builder.addMessage(msg.toOpenAiMessageParam())
        }
        return builder.build()
    }

    /**
     * 把 [ChatMessage] 映射为 SDK 消息参数。
     *
     * - 全文本：用 String content（兼容性最好）
     * - 含多模态：构造 [ChatCompletionContentPart] 数组（仅支持 Text + Image）
     */
    private fun ChatMessage.toOpenAiMessageParam(): ChatCompletionMessageParam {
        val hasMultimodal = hasMultimodalContent()
        return when (role) {
            ChatMessage.Role.SYSTEM -> ChatCompletionMessageParam.ofSystem(
                ChatCompletionSystemMessageParam.builder()
                    .content(textContent())
                    .build()
            )
            ChatMessage.Role.USER -> if (hasMultimodal) {
                val parts = content.map { it.toOpenAiContentPart() }
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .contentOfArrayOfContentParts(parts)
                        .build()
                )
            } else {
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(textContent())
                        .build()
                )
            }
            ChatMessage.Role.ASSISTANT -> ChatCompletionMessageParam.ofAssistant(
                ChatCompletionAssistantMessageParam.builder()
                    .content(textContent())
                    .build()
            )
        }
    }

    private fun ContentPart.toOpenAiContentPart(): ChatCompletionContentPart = when (this) {
        is ContentPart.Text -> ChatCompletionContentPart.ofText(
            ChatCompletionContentPartText.builder().text(text).build()
        )
        is ContentPart.Image -> ChatCompletionContentPart.ofImageUrl(
            ChatCompletionContentPartImage.builder()
                .imageUrl(
                    ChatCompletionContentPartImage.ImageUrl.builder()
                        .url(url)
                        .build()
                )
                .build()
        )
        is ContentPart.Audio, is ContentPart.Video -> {
            // OpenAI Chat Completions 当前不直接支持 audio/video URL 输入；
            // 多模态降级链里应在调用前转换为文本描述。
            ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder()
                    .text("[unsupported media type: ${this::class.simpleName}]")
                    .build()
            )
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
