package com.trae.social.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.LlmCacheInvalidator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * API Key 管理 ViewModel（IMPL-2：设置子页）。
 *
 * 按 provider 读取/保存 API Key、Base URL、模型名，并管理默认 provider。
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
            val providers = LlmProvider.values()
            val configs = providers.map { provider ->
                ProviderConfig(
                    provider = provider,
                    apiKeyPreview = runCatching { configRepository.apiKeyPreview(provider) }
                        .getOrElse { null },
                    baseUrl = runCatching { configRepository.getBaseUrl(provider) }
                        .getOrElse { null } ?: "",
                    modelName = runCatching { configRepository.getModelName(provider) }
                        .getOrElse { null } ?: "",
                )
            }
            val defaultProvider = runCatching { configRepository.getDefaultProvider() }
                .getOrElse { null }
            _state.value = ApiKeyUiState(
                loading = false,
                providerConfigs = configs,
                defaultProvider = defaultProvider,
            )
        }
    }

    fun setApiKey(provider: LlmProvider, key: String) {
        viewModelScope.launch {
            runCatching { configRepository.setApiKey(provider, key) }
                .onSuccess {
                    // P2 修复：API Key 变更后失效 LlmClient 缓存，使下次请求使用新 Key
                    cacheInvalidator.invalidateCache()
                    refreshProvider(provider)
                }
                .onFailure { Timber.w(it, "保存 API Key 失败") }
        }
    }

    fun setBaseUrl(provider: LlmProvider, baseUrl: String) {
        viewModelScope.launch {
            runCatching { configRepository.setBaseUrl(provider, baseUrl) }
                .onSuccess {
                    // P2 修复：Base URL 变更后失效 LlmClient 缓存，使下次请求使用新端点
                    cacheInvalidator.invalidateCache()
                    refreshProvider(provider)
                }
                .onFailure { Timber.w(it, "保存 Base URL 失败") }
        }
    }

    fun setModelName(provider: LlmProvider, model: String) {
        viewModelScope.launch {
            runCatching { configRepository.setModelName(provider, model) }
                .onSuccess {
                    // P2 修复：模型名变更后失效 LlmClient 缓存，使下次请求使用新模型
                    cacheInvalidator.invalidateCache()
                    refreshProvider(provider)
                }
                .onFailure { Timber.w(it, "保存模型名失败") }
        }
    }

    fun setDefaultProvider(provider: LlmProvider) {
        viewModelScope.launch {
            runCatching { configRepository.setDefaultProvider(provider) }
                .onSuccess {
                    _state.value = _state.value.copy(defaultProvider = provider)
                }
                .onFailure { Timber.w(it, "设置默认 provider 失败") }
        }
    }

    private suspend fun refreshProvider(provider: LlmProvider) {
        val current = _state.value
        val updated = current.providerConfigs.map { cfg ->
            if (cfg.provider == provider) {
                cfg.copy(
                    apiKeyPreview = runCatching { configRepository.apiKeyPreview(provider) }
                        .getOrElse { null },
                    baseUrl = runCatching { configRepository.getBaseUrl(provider) }
                        .getOrElse { cfg.baseUrl } ?: "",
                    modelName = runCatching { configRepository.getModelName(provider) }
                        .getOrElse { cfg.modelName } ?: "",
                )
            } else cfg
        }
        _state.value = current.copy(providerConfigs = updated)
    }
}

/** API Key 管理 UI 状态。 */
data class ApiKeyUiState(
    val loading: Boolean = false,
    val providerConfigs: List<ProviderConfig> = emptyList(),
    val defaultProvider: LlmProvider? = null,
)

/** 单个 provider 的配置快照。 */
data class ProviderConfig(
    val provider: LlmProvider,
    val apiKeyPreview: String?,
    val baseUrl: String,
    val modelName: String,
)
