package com.trae.social.llm

import com.trae.social.core.data.config.LlmProvider
import kotlinx.coroutines.flow.Flow

/**
 * LLM 客户端统一抽象。
 *
 * 各提供商（OpenAI / Anthropic / Gemini / 自定义）实现该接口，
 * 上层调用方无需关心底层协议差异。
 */
interface LlmClient {

    /**
     * 流式对话：逐 token 返回增量文本。
     *
     * 流式失败时，实现方应自动降级为 [chatSync] 后再以单条流的形式发出。
     */
    suspend fun chat(messages: List<ChatMessage>, config: ChatConfig): Flow<String>

    /**
     * 非流式对话：阻塞等待完整响应后一次性返回。
     */
    suspend fun chatSync(messages: List<ChatMessage>, config: ChatConfig): String

    /**
     * 连通性测试：发送一条 "ping" 用户消息，期望非空响应即视为成功。
     */
    suspend fun ping(): Boolean

    /**
     * 当前客户端对应的提供商类型。
     */
    val provider: LlmProvider
}

/**
 * 对话消息单元。
 */
data class ChatMessage(
    val role: Role,
    val content: String,
) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}

/**
 * 对话生成参数。
 *
 * @param temperature 采样温度，越高越发散。
 * @param maxTokens 单次响应最大 token 数。
 * @param jsonMode 是否要求严格 JSON 输出（仅 OpenAI 原生支持，其他提供商以 prompt 约束）。
 */
data class ChatConfig(
    val temperature: Float = 0.8f,
    val maxTokens: Int = 512,
    val jsonMode: Boolean = false,
)

// IMPL-44：LlmProvider 统一定义于 core-data 模块（含 id/displayName 元数据），
// 此处通过 api(project(":core-data")) 传递依赖复用，消除重复枚举。
