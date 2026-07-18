package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.config.LlmProtocol
import com.trae.social.core.data.config.ModelCapability
import com.trae.social.core.data.entity.LlmEndpointEntity
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.LlmCacheInvalidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * API Key / 端点管理 ViewModel（#151 重构：从按 LlmProvider 寻址改为多端点 CRUD + 排序）。
 *
 * UI 列表展示用户配置的所有端点（按 [LlmEndpointEntity.orderIndex] 升序），
 * 支持编辑端点元数据（displayName / protocol / baseUrl / model / capabilities）、
 * API Key、删除端点、拖拽排序。首位端点自动为主端点（orderIndex=0）。
 */
@HiltViewModel
class ApiKeyViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val cacheInvalidator: LlmCacheInvalidator,
) : ViewModel() {

    private val _state = MutableStateFlow(ApiKeyUiState(loading = true))
    val state: StateFlow<ApiKeyUiState> = _state.asStateFlow()

    init {
        loadAll()
    }

    private fun loadAll() {
        viewModelScope.launch {
            val endpoints = runCatching { configRepository.listEndpoints() }
                .getOrElse { emptyList() }
            val configs = endpoints.map { it.toEndpointConfig() }
            _state.value = ApiKeyUiState(
                loading = false,
                endpoints = configs,
            )
        }
    }

    /** 新增端点（用户在 UI 上点击"添加端点"时调用）。 */
    fun addEndpoint(
        displayName: String,
        protocol: LlmProtocol,
        baseUrl: String,
        model: String,
        apiKey: String,
    ) {
        viewModelScope.launch {
            runCatching {
                configRepository.addEndpoint(
                    displayName = displayName,
                    protocol = protocol,
                    baseUrl = baseUrl,
                    model = model,
                    capabilities = DEFAULT_CAPABILITIES,
                    apiKey = apiKey.takeIf { it.isNotEmpty() },
                )
            }.onSuccess {
                cacheInvalidator.invalidateCache()
                loadAll()
            }.onFailure { Timber.w(it, "新增端点失败") }
        }
    }

    /** 更新端点元数据（不含 API Key）。 */
    fun updateEndpoint(
        id: String,
        displayName: String,
        protocol: LlmProtocol,
        baseUrl: String,
        model: String,
    ) {
        viewModelScope.launch {
            runCatching {
                configRepository.updateEndpoint(
                    id = id,
                    displayName = displayName,
                    protocol = protocol,
                    baseUrl = baseUrl,
                    model = model,
                    capabilities = DEFAULT_CAPABILITIES,
                )
            }.onSuccess {
                cacheInvalidator.invalidateCache()
                loadAll()
            }.onFailure { Timber.w(it, "更新端点失败") }
        }
    }

    /** 单独保存 API Key（避免每次保存端点都需重输 Key）。 */
    fun setApiKey(endpointId: String, apiKey: String) {
        viewModelScope.launch {
            runCatching { configRepository.setEndpointApiKey(endpointId, apiKey) }
                .onSuccess {
                    cacheInvalidator.invalidateCache()
                    loadAll()
                }
                .onFailure { Timber.w(it, "保存 API Key 失败") }
        }
    }

    /** 删除端点。 */
    fun deleteEndpoint(id: String) {
        viewModelScope.launch {
            runCatching { configRepository.deleteEndpoint(id) }
                .onSuccess {
                    cacheInvalidator.invalidateCache()
                    loadAll()
                }
                .onFailure { Timber.w(it, "删除端点失败") }
        }
    }

    /**
     * 重排端点顺序。
     *
     * @param orderedIds 按新顺序给出 endpointId 列表
     */
    fun reorderEndpoints(orderedIds: List<String>) {
        viewModelScope.launch {
            runCatching { configRepository.reorderEndpoints(orderedIds) }
                .onSuccess {
                    cacheInvalidator.invalidateCache()
                    loadAll()
                }
                .onFailure { Timber.w(it, "重排端点失败") }
        }
    }

    /**
     * 把指定端点上移一位（orderIndex - 1）。常用于"设为主端点"快捷操作。
     */
    fun moveToFront(endpointId: String) {
        val current = _state.value.endpoints
        if (current.isEmpty() || current.first().id == endpointId) return
        val newOrder = listOf(endpointId) + current.filter { it.id != endpointId }.map { it.id }
        reorderEndpoints(newOrder)
    }

    private suspend fun LlmEndpointEntity.toEndpointConfig(): EndpointConfig {
        val keyPreview = runCatching { configRepository.endpointApiKeyPreview(id) }
            .getOrNull()
        return EndpointConfig(
            id = id,
            displayName = displayName,
            protocol = LlmProtocol.fromId(protocol) ?: LlmProtocol.OPENAI_COMPATIBLE,
            baseUrl = baseUrl,
            model = model,
            apiKeyPreview = keyPreview,
            orderIndex = orderIndex,
        )
    }

    companion object {
        /** 默认能力集合：所有端点均声明 TEXT + JSON_MODE_NATIVE + STREAMING。 */
        val DEFAULT_CAPABILITIES: Set<ModelCapability> = setOf(
            ModelCapability.TEXT,
            ModelCapability.JSON_MODE_NATIVE,
            ModelCapability.STREAMING,
        )
    }
}

/** API Key 管理 UI 状态。 */
data class ApiKeyUiState(
    val loading: Boolean = false,
    val endpoints: List<EndpointConfig> = emptyList(),
)

/**
 * 单个端点的配置快照。
 *
 * @param id 端点 UUID（Room @PrimaryKey）
 * @param displayName UI 展示名
 * @param protocol 协议格式（OpenAI 兼容 / Anthropic 兼容）
 * @param baseUrl Base URL
 * @param model 模型名
 * @param apiKeyPreview API Key 脱敏预览（如 "sk-1***wxyz"）
 * @param orderIndex 全局排序，0 = 主端点
 */
data class EndpointConfig(
    val id: String,
    val displayName: String,
    val protocol: LlmProtocol,
    val baseUrl: String,
    val model: String,
    val apiKeyPreview: String?,
    val orderIndex: Int,
)
