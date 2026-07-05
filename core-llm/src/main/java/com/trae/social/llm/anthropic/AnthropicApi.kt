package com.trae.social.llm.anthropic

import com.trae.social.llm.LlmHttp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Streaming

/**
 * Anthropic Messages API。
 *
 * 端点：`POST v1/messages`，Base URL 默认 https://api.anthropic.com/。
 * 鉴权头 `x-api-key` 与 `anthropic-version` 由 [com.trae.social.llm.interceptor.AuthInterceptor] 注入。
 */
interface AnthropicApi {

    /**
     * 流式对话：返回 SSE 响应体。
     * 事件类型包括 `message_start` / `content_block_delta` / `message_stop`，
     * 增量文本位于 `content_block_delta` 事件的 `delta.text`。
     */
    @Streaming
    @Headers("${LlmHttp.PROVIDER_HEADER}: ANTHROPIC")
    @POST("v1/messages")
    suspend fun streamChat(@Body body: AnthropicRequest): ResponseBody

    /**
     * 非流式对话：阻塞等待完整响应。
     */
    @Headers("${LlmHttp.PROVIDER_HEADER}: ANTHROPIC")
    @POST("v1/messages")
    suspend fun chat(@Body body: AnthropicRequest): AnthropicResponse
}

@Serializable
data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    val temperature: Float = 0.8f,
    val stream: Boolean = false,
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String,
)

@Serializable
data class AnthropicResponse(
    val content: List<AnthropicContentBlock> = emptyList(),
)

@Serializable
data class AnthropicContentBlock(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
data class AnthropicStreamEvent(
    val type: String? = null,
    val delta: AnthropicDelta? = null,
)

@Serializable
data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null,
)
