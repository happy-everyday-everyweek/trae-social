package com.trae.social.llm.gemini

import com.trae.social.llm.LlmHttp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Google Gemini API。
 *
 * 流式端点：`POST v1beta/models/{model}:streamGenerateContent`
 * 非流式端点：`POST v1beta/models/{model}:generateContent`
 * Base URL 默认 https://generativelanguage.googleapis.com/。
 * 鉴权通过 `?key=API_KEY` query 参数，由 [com.trae.social.llm.interceptor.AuthInterceptor] 注入。
 */
interface GeminiApi {

    /**
     * 流式生成：返回 HTTP chunked 响应体（非 SSE），
     * 内容为 JSON 数组 `[{candidates:...}, ...]`，逐元素到达。
     */
    @Streaming
    @Headers("${LlmHttp.PROVIDER_HEADER}: GEMINI")
    @POST("v1beta/models/{model}:streamGenerateContent")
    suspend fun streamChat(
        @Path("model") model: String,
        @Body body: GeminiRequest,
    ): ResponseBody

    /**
     * 非流式生成：阻塞等待完整响应。
     */
    @Headers("${LlmHttp.PROVIDER_HEADER}: GEMINI")
    @POST("v1beta/models/{model}:generateContent")
    suspend fun chat(
        @Path("model") model: String,
        @Body body: GeminiRequest,
    ): GeminiResponse
}

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerialName("systemInstruction") val systemInstruction: GeminiContent? = null,
    @SerialName("generationConfig") val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart> = emptyList(),
)

@Serializable
data class GeminiPart(
    val text: String? = null,
)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Float = 0.8f,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int = 512,
    @SerialName("responseMimeType") val responseMimeType: String? = null,
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
)
