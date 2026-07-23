package com.trae.social.onboarding

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.config.LlmProtocol
import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.config.ModelCapability
import com.trae.social.core.data.di.SecurePreferences
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.llm.RulesetEngine
import com.trae.social.llm.SdkExceptionClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * 引导流程 ViewModel。
 *
 * 持有 [ConfigRepository] 与 [RulesetEngine]，统一管理引导各步骤的 UI 状态：
 * - 提供商选择
 * - API Key / Base URL / 模型输入
 * - 连通性测试
 * - 配置保存与冷启动触发
 *
 * #151 重构后：旧 [LlmProvider] 枚举仅作为"预设包"（默认 Base URL / 模型 / 协议格式），
 * 实际配置以多端点形式持久化到 [com.trae.social.core.data.entity.LlmEndpointEntity] 表。
 * 引导流程中用户选择的 provider 直接决定 [LlmProtocol]，配合输入的 apiKey/baseUrl/model
 * 创建一个端点；首个端点自动成为主端点（orderIndex=0）。
 *
 * UI 状态通过 [uiState] 暴露为冷流，Composable 收集后渲染。
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val rulesetEngine: RulesetEngine,
    private val coldStartFiller: ColdStartFiller,
    // #34：直接注入 EncryptedSharedPreferences 用于历史 API Key 存储，
    // 与 ConfigRepository 内部使用的同一加密实例（命名空间隔离，键名以 history_ 前缀）。
    @SecurePreferences private val secureSharedPreferences: SharedPreferences,
) : ViewModel() {

    /**
     * 引导流程 UI 状态。
     *
     * @param selectedProvider 当前选中的 LLM 提供商（仅作为预设包用途）
     * @param apiKey 用户输入的 API Key
     * @param baseUrl 用户输入的 Base URL（按提供商预填默认值）
     * @param model 用户输入的模型名（按提供商预填推荐值）
     * @param testStatus 连通性测试状态
     * @param isSaving 是否正在保存配置并触发冷启动
     * @param completed 配置是否已保存完成（用于触发导航至 done 页）
     * @param pendingEndpointId 测试时创建/更新的端点 id；saveAndComplete 时复用该 id，
     *   避免重复测试时累积多个端点
     * @param saveError 保存配置失败的用户可读原因（主 review 第 1 轮 M1 修复）。
     *   非 null 时 UI 应展示错误并允许重试；null 表示无错误或尚未触发保存。
     * @param historyApiKeys #34：历史 API Key 列表（加密存储，最多保留 5 条，按最近使用排序）。
     *   UI 据此渲染快速选择入口，用户点击即回填到 apiKey 字段。
     */
    data class OnboardingUiState(
        val selectedProvider: LlmProvider = LlmProvider.OPENAI,
        val apiKey: String = "",
        val baseUrl: String = DEFAULT_BASE_URLS[LlmProvider.OPENAI] ?: "",
        val model: String = DEFAULT_MODELS[LlmProvider.OPENAI] ?: "",
        val testStatus: TestStatus = TestStatus.Idle,
        val isSaving: Boolean = false,
        val completed: Boolean = false,
        val pendingEndpointId: String? = null,
        val saveError: String? = null,
        val historyApiKeys: List<String> = emptyList(),
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

    init {
        // #151：触发旧 provider 配置迁移到端点表（幂等，仅当无端点且有旧配置时执行）。
        // 同时若已有端点（极端场景：用户已完成 onboarding 但被重新触发），把首个端点的
        // 元数据回填到 UI 状态，避免覆盖已有配置。
        viewModelScope.launch {
            // 主 review 第 1 轮 M2 修复：原 runCatching 会吞 CancellationException，
            // 协程取消被误判为 listEndpoints 失败。改为 try/catch 显式重抛。
            val endpoints = try {
                configRepository.listEndpoints()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "OnboardingViewModel init: listEndpoints 失败")
                return@launch
            }
            val first = endpoints.firstOrNull() ?: return@launch
            // review 第 5 轮修复：suspend I/O 不应写在 _uiState.update 的 CAS lambda 内——
            // update 是 CAS 循环，协程在 getEndpointApiKey（EncryptedSharedPreferences 解密）期间
            // 挂起且 _uiState.value 被并发改写（同 init 块另一 launch）时，CAS 失败会重新执行
            // 整个 lambda，重复解密。先在 update 外取值，再 update。
            val snapshot = _uiState.value
            val backfillApiKey = if (snapshot.apiKey.isBlank()) {
                // M2 修复：getEndpointApiKey 是 suspend，原 runCatching 吞 CancellationException。
                try {
                    configRepository.getEndpointApiKey(first.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.w(t, "OnboardingViewModel init: getEndpointApiKey 失败")
                    null
                }.orEmpty()
            } else {
                snapshot.apiKey
            }
            _uiState.update { current ->
                current.copy(
                    pendingEndpointId = first.id,
                    // 仅在用户尚未输入时回填，避免覆盖正在输入的内容
                    baseUrl = if (current.baseUrl.isBlank()) first.baseUrl else current.baseUrl,
                    model = if (current.model.isBlank()) first.model else current.model,
                    apiKey = if (current.apiKey.isBlank()) backfillApiKey else current.apiKey,
                )
            }
        }
        // #34：加载历史 API Key 列表，供 KeyInputScreen 渲染快速选择入口
        viewModelScope.launch {
            val history = try {
                loadHistoryApiKeys()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "OnboardingViewModel init: 加载历史 API Key 失败")
                emptyList()
            }
            _uiState.update { it.copy(historyApiKeys = history) }
        }
    }

    /**
     * 选择 LLM 提供商。
     *
     * 切换时自动重置 Base URL 与模型为该提供商的推荐默认值。
     * pendingEndpointId 一并重置，确保下次测试时按新 provider 重新创建端点
     * （而非复用旧 provider 创建的端点）。
     *
     * 主 review 第 2 轮修复：同时清空 saveError。用户在保存失败后切换 provider，
     * 旧 saveError 不再适用（新 provider 还没尝试保存），保留会误导 UI 显示"重试"按钮。
     *
     * review 第 5 轮修复：不再清空 apiKey。原实现置 apiKey="" 抵消了 #34 的粘贴识别——
     * 用户在 OpenAI 页粘贴 sk-ant-... 的 Anthropic Key 后点"切换到 Anthropic"，刚粘贴
     * 的 Key 被清空必须重粘。切换后保留 Key，用户可继续测试；若 Key 不匹配新 provider，
     * 连通性测试会给出 401 等明确错误，无需预先清空。
     */
    fun selectProvider(provider: LlmProvider) {
        _uiState.update { current ->
            current.copy(
                selectedProvider = provider,
                baseUrl = DEFAULT_BASE_URLS[provider] ?: "",
                model = DEFAULT_MODELS[provider] ?: "",
                testStatus = TestStatus.Idle,
                pendingEndpointId = null,
                saveError = null,
            )
        }
    }

    /** 更新 API Key 输入。 */
    fun updateApiKey(key: String) {
        // 主 review 第 2 轮修复：用户修改输入时清空 saveError——旧保存错误已不适用。
        _uiState.update { it.copy(apiKey = key.trim(), testStatus = TestStatus.Idle, saveError = null) }
    }

    /**
     * #34：从历史 Key 列表中选择一条回填到输入框。
     *
     * 用户在 KeyInputScreen 点击历史 Key 时调用。回填后 testStatus 重置为 Idle，
     * 用户需重新点击"测试连接"验证该 Key 是否仍有效。
     */
    fun selectHistoryApiKey(key: String) {
        _uiState.update {
            it.copy(apiKey = key.trim(), testStatus = TestStatus.Idle, saveError = null)
        }
    }

    /** 更新 Base URL 输入。 */
    fun updateBaseUrl(url: String) {
        _uiState.update { it.copy(baseUrl = url.trim(), testStatus = TestStatus.Idle, saveError = null) }
    }

    /** 更新模型名输入。 */
    fun updateModel(model: String) {
        _uiState.update { it.copy(model = model.trim(), testStatus = TestStatus.Idle, saveError = null) }
    }

    /**
     * 触发连通性测试。
     *
     * 流程：
     * 1. 调 [ensureEndpoint] 创建或更新端点（写入端点表 + EncryptedSharedPreferences API Key）
     * 2. 调用 [RulesetEngine.ping] 验证连通性。ping 返回 Boolean，
     *    但若 ping 内部抛出 SDK 异常（401 / 403 / 429 / 5xx / 网络错误等），
     *    会向上 propagate——这里 `runCatching` 捕获后用 [classifyError] 给出具体原因，
     *    避免用户看到「连接失败」这种无信息错误（#151 review 反馈：错误分类能力回归）。
     *
     * #288：端点 CRUD / API Key 变更后无需手动调 invalidateCache()——ConfigRepository
     * 在每个写操作内 `_endpointChanges.tryEmit(Unit)`，EndpointRegistry 订阅该流后
     * 自动 `invalidateCache()`，缓存失效由类型系统（订阅）保证而非人工记忆。
     */
    fun testConnection() {
        val current = _uiState.value
        if (current.testStatus is TestStatus.Loading) return

        // 主 review 第 2 轮修复：进入新一轮测试时清空 saveError——旧保存错误与本次测试无关，
        // 保留会导致 UI 在测试成功后仍显示"保存失败：..."和"重试"按钮。
        _uiState.update { it.copy(testStatus = TestStatus.Loading, saveError = null) }

        viewModelScope.launch {
            try {
                val endpointId = ensureEndpoint(current)

                // 主 review 第 1 轮 M2 修复：原 runCatching 会吞 CancellationException，
                // 协程取消（如用户离开页面）被误判为 ping 失败并写入 TestStatus.Error，
                // 此后即使 ViewModel 已 clear 仍向已取消的 flow 写状态。改为 try/catch
                // 显式重抛 CancellationException，让结构化并发取消语义正常传播。
                val pingOk = try {
                    rulesetEngine.ping(endpointId)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.w(t, "ping() 抛出异常")
                    // ping 内部 throw 的 SDK 异常在此分类为用户可读错误
                    _uiState.update {
                        it.copy(testStatus = TestStatus.Error(classifyError(t)))
                    }
                    return@launch
                }

                if (pingOk) {
                    // #34：测试成功后将当前 Key 写入历史（去重 + 最多保留 5 条），
                    // 下次重配时用户可从历史快速选择。失败不写入，避免历史充斥无效 Key。
                    saveApiKeyToHistory(current.apiKey)
                    // review 第 5 轮修复：loadHistoryApiKeys() 是 suspend（读 EncryptedSharedPreferences
                    // 解密），不应写在 update 的 CAS lambda 内。先取值再 update。
                    val refreshedHistory = loadHistoryApiKeys()
                    _uiState.update {
                        it.copy(
                            testStatus = TestStatus.Success,
                            historyApiKeys = refreshedHistory,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(testStatus = TestStatus.Error("连接失败：响应为空，请检查 API Key 与端点配置"))
                    }
                }
            } catch (e: CancellationException) {
                // 主 review 第 1 轮 M2 修复：协程取消（如用户离开页面 / ViewModel clear）
                // 必须向上传播，不能误判为连通性测试失败并写入 TestStatus.Error。
                throw e
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
     * 1. 调 [ensureEndpoint] 创建或更新端点（若尚未通过测试创建）
     * 2. 触发 [ColdStartFiller] 进行冷启动内容填充（RISK-14）
     * 3. 标记 completed=true，由 UI 层调用 onCompleted 跳转主界面
     *
     * 首个端点自动成为主端点（orderIndex=0），无需显式 setDefaultProvider。
     *
     * #288：端点 CRUD / API Key 变更后无需手动调 invalidateCache()——ConfigRepository
     * 在每个写操作内 `_endpointChanges.tryEmit(Unit)`，EndpointRegistry 订阅该流后
     * 自动 `invalidateCache()`，缓存失效由类型系统（订阅）保证而非人工记忆。
     *
     * @param onSaved 保存与冷启动触发完成后的回调（UI 层在此跳转至 done 页或调用 onCompleted）
     */
    fun saveAndComplete(onSaved: () -> Unit = {}) {
        if (_uiState.value.isSaving) return
        // 主 review 第 1 轮 M1 修复：进入保存流程时清空上次的 saveError，避免旧错误
        // 残留导致 UI 持续展示红色错误提示。
        _uiState.update { it.copy(isSaving = true, saveError = null) }

        viewModelScope.launch {
            try {
                val current = _uiState.value
                ensureEndpoint(current)

                // 触发冷启动内容填充（RISK-14）
                // 主 review 第 1 轮 M2 修复：原 runCatching 会吞 CancellationException，
                // 协程取消被误判为冷启动失败并继续走 completed=true 路径。改为 try/catch
                // 显式重抛 CancellationException。冷启动失败仍按非致命错误忽略。
                try {
                    coldStartFiller.triggerInitialFill()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.w(t, "冷启动内容填充失败，已忽略")
                }

                _uiState.update { it.copy(isSaving = false, completed = true) }
                onSaved()
            } catch (e: CancellationException) {
                // M2 修复：协程取消向上传播，不写入 saveError。
                throw e
            } catch (t: Throwable) {
                Timber.e(t, "保存配置失败")
                // M1 修复：原实现仅清 isSaving，UI 按钮恢复可点但无任何失败反馈，
                // 用户不知道保存没成功。改为同时写入 saveError，由 ConnectionTestScreen
                // 的 SuccessState 展示错误并提供重试。
                _uiState.update {
                    it.copy(isSaving = false, saveError = classifyError(t))
                }
            }
        }
    }

    /**
     * 跳过引导。
     *
     * 用户选择"稍后"时调用：直接回调 [onSkipped] 进入主界面，
     * 不写入端点配置；onboarding_completed 标记由 app 层的 onCompleted 回调统一写入。
     */
    fun skip(onSkipped: () -> Unit = {}) {
        onSkipped()
    }

    /**
     * 确保端点存在（按当前 UI 状态创建或更新）。
     *
     * - 若 [OnboardingUiState.pendingEndpointId] 为空：调 [ConfigRepository.addEndpoint] 新建端点
     * - 否则：调 [ConfigRepository.updateEndpoint] 更新已存在端点
     *
     * API Key 通过 [ConfigRepository.setEndpointApiKey] 单独写入 EncryptedSharedPreferences。
     * 创建/更新后返回端点 id 并写入 [OnboardingUiState.pendingEndpointId]。
     */
    private suspend fun ensureEndpoint(state: OnboardingUiState): String {
        val protocol = protocolFor(state.selectedProvider)
        // #297 修复：按 protocol 区分能力，避免给 Anthropic 端点声明 JSON_MODE_NATIVE
        // 导致 DefaultRulesetEngine 走原生 response_format 失败。
        // 与 ApiKeyViewModel.defaultCapabilitiesFor(protocol) 保持一致。
        val capabilities = when (protocol) {
            LlmProtocol.OPENAI_COMPATIBLE -> setOf(
                ModelCapability.TEXT,
                ModelCapability.JSON_MODE_NATIVE,
                ModelCapability.STREAMING,
            )
            LlmProtocol.ANTHROPIC_COMPATIBLE -> setOf(
                ModelCapability.TEXT,
                ModelCapability.STREAMING,
            )
        }
        // review 修复：KeyInputScreen 的 supportingText 承诺「留空则使用提供商官方端点」，
        // 但此前 state.baseUrl 为空时直接透传空串给 ConfigRepository，导致 ping 请求
        // 打到空 URL 报 UnknownHostException。此处对非 CUSTOM 提供商补上官方默认端点。
        // CUSTOM 不在此列——canSubmit 已要求 CUSTOM 必须填写合法 URL。
        val effectiveBaseUrl = state.baseUrl.ifBlank {
            DEFAULT_BASE_URLS[state.selectedProvider] ?: ""
        }

        val pendingId = state.pendingEndpointId
        if (pendingId != null) {
            configRepository.updateEndpoint(
                id = pendingId,
                displayName = state.selectedProvider.displayName,
                protocol = protocol,
                baseUrl = effectiveBaseUrl,
                model = state.model,
                capabilities = capabilities,
            )
            if (state.apiKey.isNotEmpty()) {
                configRepository.setEndpointApiKey(pendingId, state.apiKey)
            }
            return pendingId
        }

        val newId = configRepository.addEndpoint(
            displayName = state.selectedProvider.displayName,
            protocol = protocol,
            baseUrl = effectiveBaseUrl,
            model = state.model,
            capabilities = capabilities,
            apiKey = state.apiKey.takeIf { it.isNotEmpty() },
        )
        _uiState.update { it.copy(pendingEndpointId = newId) }
        return newId
    }

    // ==================================================================
    // #34：历史 API Key 加密存储（EncryptedSharedPreferences）
    // ==================================================================
    // 存储 key 为 `history_api_keys`，value 为多条 Key 以 "\n" 拼接的单行字符串。
    // API Key 不含换行符，分隔安全。最近使用的排在最前，最多保留 [MAX_HISTORY_KEYS] 条。
    // 与 ConfigRepository 内部 endpointApiKeyEntry 命名空间（`api_key_ep_*`）隔离。

    /**
     * 读取历史 API Key 列表，按最近使用顺序排列（最前为最近）。
     */
    private suspend fun loadHistoryApiKeys(): List<String> = withContext(Dispatchers.IO) {
        val raw = secureSharedPreferences.getString(HISTORY_API_KEYS_ENTRY, null) ?: return@withContext emptyList()
        raw.split('\n').filter { it.isNotBlank() }
    }

    /**
     * 将 [apiKey] 写入历史：去重后置于最前，截断到 [MAX_HISTORY_KEYS] 条。
     * 仅在 Key 非空时写入。
     */
    private suspend fun saveApiKeyToHistory(apiKey: String) {
        if (apiKey.isBlank()) return
        withContext(Dispatchers.IO) {
            val current = secureSharedPreferences
                .getString(HISTORY_API_KEYS_ENTRY, null)
                ?.split('\n')
                ?.filter { it.isNotBlank() }
                .orEmpty()
            // 去重：移除已存在的相同 Key，再置于最前
            val updated = listOf(apiKey) + current.filter { it != apiKey }
            val truncated = updated.take(MAX_HISTORY_KEYS)
            secureSharedPreferences.edit()
                .putString(HISTORY_API_KEYS_ENTRY, truncated.joinToString("\n"))
                .apply()
        }
    }

    /** 按 LlmProvider 预设选择协议格式（OpenAI 兼容 / Anthropic 兼容）。 */
    private fun protocolFor(provider: LlmProvider): LlmProtocol = when (provider) {
        LlmProvider.ANTHROPIC -> LlmProtocol.ANTHROPIC_COMPATIBLE
        LlmProvider.OPENAI, LlmProvider.GEMINI, LlmProvider.CUSTOM -> LlmProtocol.OPENAI_COMPATIBLE
    }

    /**
     * 将异常分类为用户可读的错误原因。
     *
     * 不依赖 className 匹配作为 SDK 异常识别门控——本 PR 的核心论点即"className 匹配
     * 依赖包名约定、不够稳健"。这里直接对任意异常尝试反射 `statusCode()`，命中即分类，
     * 未命中再走 message 正则兜底。网络异常（UnknownHost / timeout / IOException）
     * 优先于 HTTP code 分类，避免 IOException message 偶然含 3 位数字被误判为 HTTP 错误。
     */
    private fun classifyError(t: Throwable): String {
        val message = t.message.orEmpty()
        return when {
            t is UnknownHostException ||
                message.contains("UnknownHost", ignoreCase = true) ->
                "DNS 失败：无法解析主机名，请检查 Base URL"

            t is SocketTimeoutException ||
                message.contains("timeout", ignoreCase = true) ->
                "连接超时，请检查网络或调整 Base URL"

            t is IOException -> "网络错误：${message.ifBlank { "请检查网络连接" }}"

            else -> {
                // 非网络异常：通常是 SDK 抛出的 HTTP 错误（OpenAIServiceException /
                // AnthropicServiceException 子类）。先反射 statusCode()，再用 message 正则兜底。
                // #308：反射逻辑抽到 core-llm 的 SdkExceptionClassifier 共享给
                // DefaultRulesetEngine / OpenAi / Anthropic client，消除四处重复定义。
                val code = SdkExceptionClassifier.extractStatusCode(t) ?: extractHttpCodeFromMessage(message)
                when (code) {
                    401 -> "未授权（401）：API Key 无效或已过期"
                    403 -> "禁止访问（403）：API Key 无权限"
                    404 -> "端点不存在（404）：请检查 Base URL"
                    429 -> "请求频率过高（429）：稍后重试"
                    null -> "错误：${message.ifBlank { t::class.qualifiedName ?: t::class.simpleName ?: "未知错误" }}"
                    else -> "HTTP 错误（$code）"
                }
            }
        }
    }

    /**
     * 从异常消息中提取 HTTP 状态码（如 "HTTP 401 Unauthorized"）。
     *
     * 仅作为 [SdkExceptionClassifier.extractStatusCode] 反射未命中时的兜底——SDK 异常的
     * message 通常是 JSON 错误体（不一定含独立 3 位数字），非 SDK 异常（如 IOException）
     * 的 message 含 3 位数字（如 "Port 443 in use"）可能被误判，但本函数仅用于
     * 给用户展示分类后的错误原因，不参与降级链决策，宽容度可较高。
     */
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

        // #34：历史 API Key 存储常量
        /** EncryptedSharedPreferences 中历史 Key 列表的存储键 */
        private const val HISTORY_API_KEYS_ENTRY = "history_api_keys"
        /** 历史列表最大保留条数（超出按最近使用截断） */
        const val MAX_HISTORY_KEYS = 5
    }
}
