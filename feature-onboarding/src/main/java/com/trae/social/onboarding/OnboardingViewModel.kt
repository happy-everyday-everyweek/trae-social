package com.trae.social.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.llm.ChatConfig
import com.trae.social.llm.ChatMessage
import com.trae.social.llm.LlmClient
import com.trae.social.llm.LlmProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * 引导流程 ViewModel。
 *
 * 持有 [ConfigRepository] 与 [LlmProviderRegistry]，统一管理引导各步骤的 UI 状态：
 * - 提供商选择
 * - API Key / Base URL / 模型输入
 * - 连通性测试
 * - 配置保存与冷启动触发
 *
 * UI 状态通过 [uiState] 暴露为冷流，Composable 收集后渲染。
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val llmProviderRegistry: LlmProviderRegistry,
    private val coldStartFiller: ColdStartFiller,
) : ViewModel() {

    /**
     * 引导流程 UI 状态。
     *
     * @param selectedProvider 当前选中的 LLM 提供商
     * @param apiKey 用户输入的 API Key
     * @param baseUrl 用户输入的 Base URL（按提供商预填默认值）
     * @param model 用户输入的模型名（按提供商预填推荐值）
     * @param testStatus 连通性测试状态
     * @param isSaving 是否正在保存配置并触发冷启动
     * @param completed 配置是否已保存完成（用于触发导航至 done 页）
     */
    data class OnboardingUiState(
        val selectedProvider: LlmProvider = LlmProvider.OPENAI,
        val apiKey: String = "",
        val baseUrl: String = DEFAULT_BASE_URLS[LlmProvider.OPENAI] ?: "",
        val model: String = DEFAULT_MODELS[LlmProvider.OPENAI] ?: "",
        val testStatus: TestStatus = TestStatus.Idle,
        val isSaving: Boolean = false,
        val completed: Boolean = false,
    )

    /**
     * 连通性测试状态机。
     */
    sealed interface TestStatus {
        /** 未测试 */
        data object Idle : TestStatus

        /** 测试中 */
        data object Loading : TestStatus

        /** 测试成功 */
        data object Success : TestStatus

        /** 测试失败，附带可读原因 */
        data class Error(val message: String) : TestStatus
    }

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /**
     * 选择 LLM 提供商。
     *
     * 切换时自动重置 Base URL 与模型为该提供商的推荐默认值（仅当用户未自定义时）。
     */
    fun selectProvider(provider: LlmProvider) {
        _uiState.update { current ->
            current.copy(
                selectedProvider = provider,
                baseUrl = DEFAULT_BASE_URLS[provider] ?: "",
                model = DEFAULT_MODELS[provider] ?: "",
                testStatus = TestStatus.Idle,
            )
        }
    }

    /** 更新 API Key 输入。 */
    fun updateApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key.trim(), testStatus = TestStatus.Idle) }
    }

    /** 更新 Base URL 输入。 */
    fun updateBaseUrl(url: String) {
        _uiState.update { it.copy(baseUrl = url.trim(), testStatus = TestStatus.Idle) }
    }

    /** 更新模型名输入。 */
    fun updateModel(model: String) {
        _uiState.update { it.copy(model = model.trim(), testStatus = TestStatus.Idle) }
    }

    /**
     * 触发连通性测试。
     *
     * 流程：
     * 1. 将当前输入临时写入 [ConfigRepository]（供 [com.trae.social.llm.LlmConfigProvider] 读取）
     * 2. 失效 [LlmProviderRegistry] 缓存（强制按新配置重建客户端）
     * 3. 调用 [LlmClient.ping] 验证连通性
     * 4. 失败时再次调用 [LlmClient.chatSync] 捕获具体异常以分类错误原因
     */
    fun testConnection() {
        val current = _uiState.value
        if (current.testStatus is TestStatus.Loading) return

        _uiState.update { it.copy(testStatus = TestStatus.Loading) }

        viewModelScope.launch {
            try {
                persistConfig(current)
                llmProviderRegistry.invalidateCache()

                val client = llmProviderRegistry.getClient(current.selectedProvider)
                val success = try {
                    client.ping()
                } catch (t: Throwable) {
                    Timber.w(t, "ping() 抛出异常")
                    false
                }

                if (success) {
                    _uiState.update { it.copy(testStatus = TestStatus.Success) }
                } else {
                    val errorMessage = classifyErrorByProbing(client)
                    _uiState.update { it.copy(testStatus = TestStatus.Error(errorMessage)) }
                }
            } catch (t: Throwable) {
                Timber.e(t, "连通性测试流程异常")
                _uiState.update {
                    it.copy(testStatus = TestStatus.Error(classifyError(t)))
                }
            }
        }
    }

    /**
     * 保存配置并完成引导。
     *
     * 1. 持久化当前输入至 [ConfigRepository]
     * 2. 标记默认提供商
     * 3. 失效 LLM 客户端缓存（使下次调用按新配置创建）
     * 4. 触发 [ColdStartFiller] 进行冷启动内容填充（RISK-14）
     * 5. 标记 completed=true，由 UI 层调用 onCompleted 跳转主界面
     *
     * @param onSaved 保存与冷启动触发完成后的回调（UI 层在此跳转至 done 页或调用 onCompleted）
     */
    fun saveAndComplete(onSaved: () -> Unit = {}) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            try {
                val current = _uiState.value
                persistConfig(current)
                configRepository.setDefaultProvider(current.selectedProvider)
                llmProviderRegistry.invalidateCache()

                // 触发冷启动内容填充（RISK-14）
                runCatching { coldStartFiller.triggerInitialFill() }
                    .onFailure { Timber.w(it, "冷启动内容填充失败，已忽略") }

                _uiState.update { it.copy(isSaving = false, completed = true) }
                onSaved()
            } catch (t: Throwable) {
                Timber.e(t, "保存配置失败")
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    /**
     * 跳过引导。
     *
     * 用户选择"稍后"时调用：直接回调 [onSkipped] 进入主界面，
     * 不写入 LLM 配置；onboarding_completed 标记由 app 层的 onCompleted 回调统一写入。
     */
    fun skip(onSkipped: () -> Unit = {}) {
        onSkipped()
    }

    /**
     * 持久化当前 UI 状态至 [ConfigRepository]。
     *
     * 仅写入非空字段；空字符串不覆盖已有值（避免清空历史配置）。
     */
    private suspend fun persistConfig(state: OnboardingUiState) {
        val provider = state.selectedProvider
        if (state.apiKey.isNotEmpty()) {
            configRepository.setApiKey(provider, state.apiKey)
        }
        if (state.baseUrl.isNotEmpty()) {
            configRepository.setBaseUrl(provider, state.baseUrl)
        }
        if (state.model.isNotEmpty()) {
            configRepository.setModelName(provider, state.model)
        }
    }

    /**
     * 通过再次调用 chatSync 捕获异常以分类错误原因。
     *
     * [LlmClient.ping] 内部使用 runCatching 吞掉异常，仅返回 Boolean，
     * 此处主动发起一次 chatSync 调用以获取具体的异常类型，便于向用户展示
     * 401 / 超时 / DNS 等可读原因。仅在 ping 失败时调用，避免额外网络开销。
     */
    private suspend fun classifyErrorByProbing(client: LlmClient): String {
        return try {
            client.chatSync(
                listOf(ChatMessage(ChatMessage.Role.USER, "ping")),
                ChatConfig(temperature = 0.0f, maxTokens = 8),
            )
            // ping 返回 false 但 chatSync 未抛异常：响应可能为空，给出通用提示
            "连接失败：响应为空，请检查 API Key 与端点配置"
        } catch (t: Throwable) {
            classifyError(t)
        }
    }

    /**
     * 将异常分类为用户可读的错误原因。
     */
    private fun classifyError(t: Throwable): String {
        val message = t.message.orEmpty()
        val className = t::class.qualifiedName ?: t::class.simpleName.orEmpty()
        return when {
            t is UnknownHostException ||
                message.contains("UnknownHost", ignoreCase = true) ->
                "DNS 失败：无法解析主机名，请检查 Base URL"

            t is SocketTimeoutException ||
                message.contains("timeout", ignoreCase = true) ->
                "连接超时，请检查网络或调整 Base URL"

            t is IOException -> "网络错误：${message.ifBlank { "请检查网络连接" }}"

            // retrofit2.HttpException 通过类名识别，避免本模块直接依赖 Retrofit
            className.contains("HttpException") -> {
                val code = extractHttpCode(t) ?: extractHttpCodeFromMessage(message)
                when (code) {
                    401 -> "未授权（401）：API Key 无效或已过期"
                    403 -> "禁止访问（403）：API Key 无权限"
                    404 -> "端点不存在（404）：请检查 Base URL"
                    429 -> "请求频率过高（429）：稍后重试"
                    null -> "HTTP 错误：${message.ifBlank { "未知状态码" }}"
                    else -> "HTTP 错误（$code）"
                }
            }

            message.contains("401") -> "未授权（401）：API Key 无效或已过期"
            message.contains("403") -> "禁止访问（403）：API Key 无权限"
            message.contains("404") -> "端点不存在（404）：请检查 Base URL"
            message.contains("429") -> "请求频率过高（429）：稍后重试"

            else -> "错误：${message.ifBlank { className.ifBlank { "未知错误" } }}"
        }
    }

    /** 通过反射读取 retrofit2.HttpException.code()，失败返回 null。 */
    private fun extractHttpCode(t: Throwable): Int? = runCatching {
        val method = t::class.java.getMethod("code")
        (method.invoke(t) as? Int)
    }.getOrNull()

    /** 从异常消息中提取 HTTP 状态码（如 "HTTP 401 Unauthorized"）。 */
    private fun extractHttpCodeFromMessage(message: String): Int? {
        val regex = Regex("""\b(4\d{2}|5\d{2})\b""")
        return regex.find(message)?.value?.toIntOrNull()
    }

    companion object {
        /**
         * 各提供商默认 Base URL（用户可在 KeyInputScreen 修改）。
         */
        val DEFAULT_BASE_URLS: Map<LlmProvider, String> = mapOf(
            LlmProvider.OPENAI to "https://api.openai.com",
            LlmProvider.ANTHROPIC to "https://api.anthropic.com",
            LlmProvider.GEMINI to "https://generativelanguage.googleapis.com",
            LlmProvider.CUSTOM to "",
        )

        /**
         * 各提供商推荐模型名（用户可在 KeyInputScreen 修改）。
         */
        val DEFAULT_MODELS: Map<LlmProvider, String> = mapOf(
            LlmProvider.OPENAI to "gpt-4o-mini",
            LlmProvider.ANTHROPIC to "claude-3-5-sonnet-20240620",
            LlmProvider.GEMINI to "gemini-1.5-flash",
            LlmProvider.CUSTOM to "gpt-4o-mini",
        )
    }
}
