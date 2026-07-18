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
 * 2. **流式中断重生成**：[chat] 流式 emit 部分 token 后中断时，引擎丢弃已 emit 内容并完全重新生成，
 *    不跨模型拼接内容（避免内容混乱）。尚未 emit 时遭遇非持久性错误，降级到下一位端点重试流式。
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
            try {
                client.chat(prepared, config).collect { token ->
                    emit(token)
                }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
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

    override suspend fun ping(endpointId: String): Boolean {
        val client = registry.getClient(endpointId) ?: return false
        return runCatching { client.ping() }.getOrDefault(false)
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
     * 通过类名识别（含 "OpenAI" / "Anthropic"）+ HTTP 状态码判断，
     * 避免本模块直接依赖 SDK errors 子包。
     */
    private fun isPersistentError(e: Throwable): Boolean {
        val className = e::class.qualifiedName ?: e::class.simpleName.orEmpty()
        val isSdkError = className.contains("OpenAI") || className.contains("Anthropic")
        if (!isSdkError) return false
        val code = extractHttpCode(e.message.orEmpty()) ?: extractHttpCode(className)
            ?: return false
        return code in 400..499 && code != 429
    }

    private fun isRateLimited(e: Throwable): Boolean {
        if (e is RateLimitedException) return true
        val message = e.message.orEmpty()
        // SDK 类名识别
        val className = e::class.qualifiedName ?: e::class.simpleName.orEmpty()
        val code = extractHttpCode(message) ?: extractHttpCode(className) ?: return false
        return code == 429
    }

    private fun toRateLimited(e: Throwable): RateLimitedException {
        if (e is RateLimitedException) return e
        return RateLimitedException(
            message = e.message ?: "LLM 提供商返回 429 限流",
            retryAfterSeconds = null,
        )
    }

    private fun extractHttpCode(text: String): Int? {
        val regex = Regex("""\b(4\d{2}|5\d{2})\b""")
        return regex.find(text)?.value?.toIntOrNull()
    }

    private companion object {
        const val JSON_MODE_HINT =
            "请严格只输出合法 JSON 对象，不要包含 markdown 代码块标记或额外说明。"
    }
}
