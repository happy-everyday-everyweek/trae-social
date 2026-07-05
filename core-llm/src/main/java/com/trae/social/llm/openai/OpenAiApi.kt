package com.trae.social.llm.openai

import com.trae.social.llm.LlmHttp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Streaming

/**
 * OpenAI Chat Completions API（兼容 OpenAI 协议的自定义端点也使用此接口）。
 *
 * 端点：`POST v1/chat/completions`，Base URL 默认 https://api.openai.com/，
 * 可通过 [com.trae.social.llm.LlmConfigProvider.getBaseUrl] 自定义。
 */
interface OpenAiApi {

    /**
     * 流式对话：返回 SSE 响应体，调用方按 `data: {json}\n\n` 行解析，
     * 终止标记为 `data: [DONE]`。
     */
    @Streaming
    @Headers("${LlmHttp.PROVIDER_HEADER}: OPENAI")
    @POST("v1/chat/completions")
    suspend fun streamChat(@Body body: OpenAiRequest): ResponseBody

    /**
     * 非流式对话：阻塞等待完整响应。
     */
    @Headers("${LlmHttp.PROVIDER_HEADER}: OPENAI")
    @POST("v1/chat/completions")
    suspend fun chat(@Body body: OpenAiRequest): OpenAiResponse
}

@Serializable
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Float = 0.8f,
    @SerialName("max_tokens") val maxTokens: Int = 512,
    val stream: Boolean = false,
    @SerialName("response_format") val responseFormat: OpenAiResponseFormat? = null,
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OpenAiResponseFormat(
    val type: String = "json_object",
)

@Serializable
data class OpenAiResponse(
    val choices: List<OpenAiChoice> = emptyList(),
)

@Serializable
data class OpenAiChoice(
    val message: OpenAiMessage? = null,
    val delta: OpenAiMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)
