package com.trae.social.llm

import kotlinx.coroutines.flow.Flow

/**
 * 单端点 LLM 客户端抽象（#151 重构后为引擎内部适配器，不再对外暴露）。
 *
 * 旧版 [LlmClient] 暴露 `provider: LlmProvider`，按 provider 寻址；新版以
 * [endpointId] + [capabilities] 寻址，由 [EndpointRegistry] 按 endpointId 缓存实例。
 *
 * 上层调用方应使用 [RulesetEngine]（极简 `chat/chatSync/ping`），不再直接持有 [LlmClient]。
 */
interface LlmClient {

    /** 流式对话：逐 token 返回增量文本。 */
    suspend fun chat(messages: List<ChatMessage>, config: ChatConfig): Flow<String>

    /** 非流式对话：阻塞等待完整响应后一次性返回。 */
    suspend fun chatSync(messages: List<ChatMessage>, config: ChatConfig): String

    /** 连通性测试：发送 ping，期望非空响应即视为成功。 */
    suspend fun ping(): Boolean

    /** 端点 id（取代旧版 `provider` 字段）。 */
    val endpointId: String

    /** 端点能力集合。 */
    val capabilities: Set<com.trae.social.core.data.config.ModelCapability>
}
