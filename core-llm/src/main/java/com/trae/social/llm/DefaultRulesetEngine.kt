package com.trae.social.llm

import com.trae.social.core.data.config.ModelCapability
import com.trae.social.core.data.entity.LlmEndpointEntity
import com.trae.social.llm.interceptor.RateLimitedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 默认规则集引擎实现（#151 重构核心）。
 *
 * 包装 [EndpointRegistry]，对上层提供极简 `chat / chatSync / ping` 三个语义。
 * 旧上层调用方（Worker / FeedbackAgent / EventTextPreParser）从 `LlmProviderRegistry.getDefaultClient().chatSync(...)`
 * 迁移到 `RulesetEngine.chatSync(...)`，不再直接持有 [LlmClient]。
 *
 * **默认规则集行为**：
 * 1. **主模型降级链**：[chatSync] 在主模型失败（非持久性错误）后，依次尝试降级链上的下一个端点。
 *    持久性错误（4xx 非 429）不降级，直接抛出。
 * 2. **流式中断不降级**：[chat] 流式 emit 部分 token 后中断时，引擎直接抛出 `IOException`，
 *    不进入下一端点的降级链——避免下游消费者收到「端点 A 部分内容 + 端点 B 完整内容」
 *    的跨模型拼接。尚未 emit 时遭遇非持久性错误，降级到下一位端点重试流式。
 * 3. **JSON mode prompt 降级**：当请求 [ChatConfig.jsonMode]=true 但端点未声明 [ModelCapability.JSON_MODE_NATIVE]
 *    时，在 system prompt 中追加 JSON 约束指令（端点原生 response_format 由 client 内部处理）。
 * 4. **429 兼容**：SDK 抛出的 429 异常被转换为 [RateLimitedException] 向上传递，
 *    使各 Worker 既有 `catch (RateLimitedException)` 分支继续生效。
 *
 * 该实现不感知多模态预处理管道（图像 URL → base64 等）；当前所有调用方均为纯文本，
 * 多模态降级链留待后续接入。
 */
@Singleton
class DefaultRulesetEngine @Inject constructor(
    private val registry: EndpointRegistry,
) : RulesetEngine {

    override suspend fun chat(
        messages: List<ChatMessage>,
        config: ChatConfig,
        rulesetId: String?,
    ): Flow<String> = flow {
        val endpoints = registry.listEndpoints()
        if (endpoints.isEmpty()) {
            throw IllegalStateException("未配置任何 LLM 端点，无法发起对话")
        }
        var lastError: Throwable? = null
        for (endpoint in endpoints) {
            val client = registry.getClient(endpoint.id) ?: continue
            val prepared = prepareMessages(messages, config, endpoint)
            var emitted = false
            try {
                client.chat(prepared, config).collect { token ->
                    emit(token)
                    emitted = true
                }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                // 已 emit 部分 token 后中断（client 包装的 streaming truncated），
                // 直接抛出不进入下一端点——避免跨模型内容拼接。
                if (emitted) {
                    Timber.w(e, "DefaultRulesetEngine.chat 流式 emit 后中断，不降级 endpoint=%s", endpoint.id)
                    throw e
                }
                if (isPersistentError(e)) {
                    Timber.w(e, "DefaultRulesetEngine.chat 持久性错误，不降级 endpoint=%s", endpoint.id)
                    throw e
                }
                if (isRateLimited(e)) {
                    throw toRateLimited(e)
                }
                Timber.w(e, "DefaultRulesetEngine.chat 失败，尝试降级到下一端点 endpoint=%s", endpoint.id)
                // 继续下一端点
            }
        }
        throw lastError ?: IllegalStateException("无可用端点")
    }

    override suspend fun chatSync(
        messages: List<ChatMessage>,
        config: ChatConfig,
        rulesetId: String?,
    ): String {
        val endpoints = registry.listEndpoints()
        if (endpoints.isEmpty()) {
            throw IllegalStateException("未配置任何 LLM 端点，无法发起对话")
        }
        var lastError: Throwable? = null
        for (endpoint in endpoints) {
            val client = registry.getClient(endpoint.id) ?: continue
            val prepared = prepareMessages(messages, config, endpoint)
            val result = runCatching { client.chatSync(prepared, config) }
            result.onSuccess { return it }
            result.onFailure { e ->
                if (e is CancellationException) throw e
                lastError = e
                if (isPersistentError(e)) {
                    Timber.w(e, "DefaultRulesetEngine.chatSync 持久性错误，不降级 endpoint=%s", endpoint.id)
                    throw e
                }
                if (isRateLimited(e)) {
                    throw toRateLimited(e)
                }
                Timber.w(e, "DefaultRulesetEngine.chatSync 失败，尝试降级到下一端点 endpoint=%s", endpoint.id)
                // 继续下一端点
            }
        }
        throw lastError ?: IllegalStateException("无可用端点")
    }

    /**
     * 连通性测试：向指定端点发送最小请求。
     *
     * 异常**向上抛出**而非吞掉——调用方（如 [com.trae.social.onboarding.OnboardingViewModel]）
     * 用 `runCatching` 捕获后可分类 SDK 异常给出具体错误原因（401 / 403 / 5xx / 网络错误等）。
     *
     * 端点不存在 / client 创建失败时返回 false（非异常路径，调用方按 Boolean 处理）。
     */
    override suspend fun ping(endpointId: String): Boolean {
        val client = registry.getClient(endpointId) ?: return false
        // 不吞异常：让调用方拿到 SDK 4xx / 5xx / IOException 后分类提示用户。
        // 旧实现用 runCatching { ... }.getOrDefault(false) 导致 OnboardingViewModel.testConnection
        // 无法区分 401 / 403 / 5xx / 网络错误（#151 review 反馈：错误分类能力回归）。
        return client.ping()
    }

    /**
     * 若端点不支持原生 JSON mode，在 system prompt 中追加 JSON 约束指令做 prompt 降级。
     *
     * 端点声明 [ModelCapability.JSON_MODE_NATIVE] 时由 client 内部走原生 `response_format`，
     * 引擎层不做额外处理。
     */
    private fun prepareMessages(
        messages: List<ChatMessage>,
        config: ChatConfig,
        endpoint: LlmEndpointEntity,
    ): List<ChatMessage> {
        if (!config.jsonMode) return messages
        val capabilities = ModelCapability.parseSet(endpoint.capabilities)
        if (ModelCapability.JSON_MODE_NATIVE in capabilities) return messages
        // 检查是否已有 system prompt，有则追加约束，无则插入新的 system 消息
        val systemIdx = messages.indexOfFirst { it.role == ChatMessage.Role.SYSTEM }
        if (systemIdx < 0) {
            return listOf(ChatMessage(ChatMessage.Role.SYSTEM, JSON_MODE_HINT)) + messages
        }
        return messages.mapIndexed { idx, msg ->
            if (idx == systemIdx) {
                ChatMessage(msg.role, msg.textContent() + "\n\n" + JSON_MODE_HINT)
            } else {
                msg
            }
        }
    }

    /**
     * 判断异常是否为持久性 HTTP 错误（4xx 非 429）。
     *
     * OpenAI / Anthropic SDK 的具体异常类（`BadRequestException` / `UnauthorizedException` /
     * `PermissionDeniedException` / `NotFoundException` / `UnprocessableEntityException` 等）
     * 均继承自 `OpenAIServiceException` / `AnthropicServiceException`，统一暴露
     * `int statusCode()` 方法。这里用反射读取该方法，避免本模块直接依赖 SDK errors 子包。
     *
     * 旧实现用 `className.contains("OpenAI"/"Anthropic")` 大小写敏感匹配，但 SDK 异常
     * 简单名不含厂商前缀、包名为小写 `com.openai.errors` / `com.anthropic.errors`，
     * 恒返回 false，导致持久性错误被错误降级遍历所有端点。详见 PR #264 review。
     */
    private fun isPersistentError(e: Throwable): Boolean {
        val code = extractSdkStatusCode(e) ?: extractHttpCode(e.message.orEmpty()) ?: return false
        return code in 400..499 && code != 429
    }

    private fun isRateLimited(e: Throwable): Boolean {
        if (e is RateLimitedException) return true
        val code = extractSdkStatusCode(e) ?: extractHttpCode(e.message.orEmpty()) ?: return false
        return code == 429
    }

    private fun toRateLimited(e: Throwable): RateLimitedException {
        if (e is RateLimitedException) return e
        return RateLimitedException(
            message = e.message ?: "LLM 提供商返回 429 限流",
            retryAfterSeconds = null,
        )
    }

    /**
     * 通过反射读取 SDK 异常的 `statusCode()` 方法（OpenAI / Anthropic SDK 同名）。
     *
     * 已通过 javap 验证：`com.openai.errors.OpenAIServiceException.statusCode() : int`
     * 与 `com.anthropic.errors.AnthropicServiceException.statusCode() : int`，
     * 子类（`BadRequestException` / `RateLimitException` 等）继承并 override 该方法。
     *
     * 非 SDK 异常（如 `IOException`）无此方法，返回 null 由调用方走 message 兜底。
     */
    private fun extractSdkStatusCode(e: Throwable): Int? = runCatching {
        val method = e::class.java.getMethod("statusCode")
        (method.invoke(e) as? Int) ?: (method.invoke(e) as? Number)?.toInt()
    }.getOrNull()

    private fun extractHttpCode(text: String): Int? {
        val regex = Regex("""\b(4\d{2}|5\d{2})\b""")
        return regex.find(text)?.value?.toIntOrNull()
    }

    private companion object {
        const val JSON_MODE_HINT =
            "请严格只输出合法 JSON 对象，不要包含 markdown 代码块标记或额外说明。"
    }
}
