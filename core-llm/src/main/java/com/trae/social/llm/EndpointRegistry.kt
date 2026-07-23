package com.trae.social.llm

/**
 * 端点注册中心抽象（#306：DIP——[DefaultRulesetEngine] 依赖此接口而非具体类，
 * 单元测试可注入 stub / fake，无需启动 Hilt 与 SDK client）。
 *
 * #151：取代按 [com.trae.social.core.data.config.LlmProvider] 寻址的旧
 * [LlmProviderRegistry]。#307：公共 API 不再暴露 Room 持久化层
 * `LlmEndpointEntity`，统一以 core-llm 拥有的 [EndpointSnapshot] 作为端点元数据载体。
 *
 * - [getClient]：按 endpointId 懒创建并缓存 [LlmClient] 实例。
 * - [getDefaultClient]：返回 orderIndex=0 的端点对应 client（主模型）。
 * - [listEndpoints] / [getEndpoint]：返回端点元数据快照，供 UI / 引擎选择降级链使用。
 * - [invalidateCache] / [invalidate]：清空缓存的 client 实例。端点配置变更后调用，
 *   也可通过订阅 [EndpointConfigProvider.observeEndpointChanges] 自动触发。
 *
 * 默认实现见 [DefaultEndpointRegistry]。
 */
interface EndpointRegistry {

    /**
     * 按 endpointId 获取 client。不存在则懒创建并缓存。
     *
     * 端点不存在 / 需鉴权协议的 API Key 缺失时返回 null（调用方应处理 null 情况）。
     */
    suspend fun getClient(endpointId: String): LlmClient?

    /**
     * 获取主模型 client（orderIndex=0）。
     *
     * 端点列表为空时返回 null（调用方应处理：通常引导用户配置端点）。
     */
    suspend fun getDefaultClient(): LlmClient?

    /**
     * 列出所有端点（按 orderIndex 升序）。供 UI / 引擎选择降级链使用。
     */
    suspend fun listEndpoints(): List<EndpointSnapshot>

    /** 按 id 获取端点（不走缓存，每次读 Room）。 */
    suspend fun getEndpoint(id: String): EndpointSnapshot?

    /**
     * 清空所有缓存的 client 实例。
     *
     * 在以下场景应调用（或通过订阅 [EndpointConfigProvider.observeEndpointChanges] 自动触发）：
     * - 用户增删改端点
     * - API Key 变更
     * - 端点 reorder
     */
    suspend fun invalidateCache()

    /** 按 endpointId 失效单个 client（保留其他）。 */
    suspend fun invalidate(endpointId: String)
}
