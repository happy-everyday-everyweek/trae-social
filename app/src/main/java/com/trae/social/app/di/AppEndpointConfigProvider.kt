package com.trae.social.app.di

import com.trae.social.core.data.entity.LlmEndpointEntity
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.llm.EndpointConfigProvider
import com.trae.social.llm.EndpointSnapshot
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EndpointConfigProvider] 的 app 模块实现（#151 重构；#307：负责持久化 entity →
 * core-llm 拥有的 [EndpointSnapshot] 映射，让 core-llm 公共 API 不再暴露 Room 类型）。
 *
 * 桥接 core-data 的 [ConfigRepository]（异步、按需读取 Room + EncryptedSharedPreferences）
 * 与 core-llm 的 [EndpointConfigProvider]（suspend，供 [com.trae.social.llm.EndpointRegistry] 调用）。
 *
 * 取代旧 [com.trae.social.app.di.AppLlmConfigProvider]（按 [com.trae.social.core.data.config.LlmProvider]
 * 寻址的旧抽象），统一以 endpointId 为寻址键。
 *
 * 端点变更事件流由 [ConfigRepository.endpointChanges] 直接转发，
 * [com.trae.social.llm.EndpointRegistry] 收集后失效缓存的 SDK client 实例。
 */
@Singleton
class AppEndpointConfigProvider @Inject constructor(
    private val configRepository: ConfigRepository,
) : EndpointConfigProvider {

    override suspend fun listEndpoints(): List<EndpointSnapshot> {
        // ConfigRepository.listEndpoints 内部会触发旧 provider 配置迁移（幂等）
        return configRepository.listEndpoints().map { it.toSnapshot() }
    }

    override suspend fun getEndpoint(id: String): EndpointSnapshot? {
        return configRepository.getEndpoint(id)?.toSnapshot()
    }

    override suspend fun getEndpointApiKey(endpointId: String): String? {
        return configRepository.getEndpointApiKey(endpointId)
    }

    override fun observeEndpointChanges(): Flow<Unit> {
        return configRepository.endpointChanges
    }

    private fun LlmEndpointEntity.toSnapshot(): EndpointSnapshot = EndpointSnapshot(
        id = id,
        displayName = displayName,
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
        capabilities = capabilities,
        orderIndex = orderIndex,
    )
}
