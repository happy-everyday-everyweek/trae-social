package com.trae.social.core.data.repository

/**
 * LLM 客户端缓存失效接口。
 *
 * P2 修复：API Key / Base URL / 模型名变更后需失效已缓存的 LlmClient 实例，
 * 否则旧配置的客户端会持续被复用。本接口定义于 core-data 以避免 feature 模块
 * 直接依赖 core-llm，实际绑定由 app 模块通过 LlmProviderRegistry 提供。
 */
fun interface LlmCacheInvalidator {
    /** 清空所有缓存的 LLM 客户端实例。 */
    suspend fun invalidateCache()
}
