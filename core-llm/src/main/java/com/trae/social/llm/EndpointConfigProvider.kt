package com.trae.social.llm

import kotlinx.coroutines.flow.Flow

/**
 * 端点配置提供者抽象（#151：取代按 [com.trae.social.core.data.config.LlmProvider] 寻址的
 * 旧 [LlmConfigProvider] 的端点相关部分；#307：不再暴露 Room 持久化层 `LlmEndpointEntity`）。
 *
 * 由 app 模块注入实现（基于 [com.trae.social.core.data.repository.ConfigRepository]），
 * core-llm 模块的 [EndpointRegistry] / [DefaultRulesetEngine] 通过该接口读取端点列表
 * 与对应 API Key。
 *
 * 所有方法为 suspend，避免在主线程上 runBlocking 导致 ANR。
 */
interface EndpointConfigProvider {

    /**
     * 列出所有端点（按 orderIndex 升序）。
     * 首次调用时触发旧 provider 配置迁移（幂等）。
     */
    suspend fun listEndpoints(): List<EndpointSnapshot>

    /** 按 id 获取端点。 */
    suspend fun getEndpoint(id: String): EndpointSnapshot?

    /** 获取指定端点的 API Key（EncryptedSharedPreferences）。 */
    suspend fun getEndpointApiKey(endpointId: String): String?

    /**
     * 端点变更事件流。任何端点 CRUD / reorder / API Key 变更后发出 Unit。
     * [EndpointRegistry] 收集后失效缓存的 SDK client 实例。
     */
    fun observeEndpointChanges(): Flow<Unit>
}
