package com.trae.social.llm

import com.trae.social.core.data.config.ModelCapability
import com.trae.social.llm.interceptor.RateLimitedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [DefaultRulesetEngine] 单元测试（#306：DIP 重构后可注入 stub [EndpointRegistry] /
 * [LlmClient]，无需启动 Hilt / SDK / 网络层；旧实现因依赖具体类无法这样测）。
 *
 * 覆盖默认规则集核心行为：
 * 1. 主模型降级链（chatSync）：非持久性错误降级，持久性错误（4xx 非 429）不降级直接抛。
 * 2. 流式中断不降级（chat）：emit 部分后中断抛 IOException 不进入下一端点。
 * 3. JSON mode prompt 降级：endpoint 未声明 JSON_MODE_NATIVE 时追加 JSON_MODE_HINT。
 * 4. 429 兼容：SDK 抛 429 转换为 [RateLimitedException]。
 * 5. ping 端点不存在返回 false；client.ping() 异常向上抛。
 * 6. 所有端点 API Key 缺失（getClient 全 null）时抛"所有端点均不可用"。
 *
 * 注意：所有用例用 `runBlocking` 而非 `runTest` —— assertThrows 需要同步拿到异常，
 * 与 `runTest` 的 TestScope 语义混用容易导致 CancellationException 被框架特殊处理。
 */
class DefaultRulesetEngineTest {

    @Test
    fun `chatSync 主模型成功直接返回不降级`() {
        val registry = FakeRegistry(
            endpoints = listOf(snapshot("a", 0)),
            clients = mapOf("a" to FakeClient("a", result = "ok")),
        )
        val engine = DefaultRulesetEngine(registry)
        val result = runBlocking { engine.chatSync(emptyList(), ChatConfig()) }
        assertEquals("ok", result)
    }

    @Test
    fun `chatSync 非持久性错误降级到下一端点`() {
        val registry = FakeRegistry(
            endpoints = listOf(snapshot("a", 0), snapshot("b", 1)),
            clients = mapOf(
                "a" to FakeClient("a", syncError = IOException("connection reset")),
                "b" to FakeClient("b", result = "fallback"),
            ),
        )
        val engine = DefaultRulesetEngine(registry)
        val result = runBlocking { engine.chatSync(emptyList(), ChatConfig()) }
        assertEquals("fallback", result)
    }

    @Test
    fun `chatSync 持久性 HTTP 错误不降级直接抛出`() {
        // 401 Unauthorized 是持久性错误（4xx 非 429），不应降级到端点 b
        val registry = FakeRegistry(
            endpoints = listOf(snapshot("a", 0), snapshot("b", 1)),
            clients = mapOf(
                "a" to FakeClient("a", syncError = SdkServiceException(401)),
                "b" to FakeClient("b", result = "fallback"),
            ),
        )
        val engine = DefaultRulesetEngine(registry)
        val ex = assertThrows(SdkServiceException::class.java) {
            runBlocking { engine.chatSync(emptyList(), ChatConfig()) }
        }
        assertEquals(401, ex.statusCode())
    }

    @Test
    fun `chatSync 429 转换为 RateLimitedException`() {
        val registry = FakeRegistry(
            endpoints = listOf(snapshot("a", 0)),
            clients = mapOf("a" to FakeClient("a", syncError = SdkServiceException(429))),
        )
        val engine = DefaultRulesetEngine(registry)
        val ex = assertThrows(RateLimitedException::class.java) {
            runBlocking { engine.chatSync(emptyList(), ChatConfig()) }
        }
        // cause 应保留原始 429 异常，便于排查
        assertNotNull(ex.cause)
        assertTrue(ex.cause is SdkServiceException)
        assertEquals(429, (ex.cause as SdkServiceException).statusCode())
    }

    @Test
    fun `chatSync 所有端点 getClient 返回 null 时抛所有端点均不可用`() {
        val registry = FakeRegistry(
            endpoints = listOf(snapshot("a", 0)),
            clients = emptyMap(), // 模拟 API Key 缺失 → getClient 全 null
        )
        val engine = DefaultRulesetEngine(registry)
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.chatSync(emptyList(), ChatConfig()) }
        }
        assertEquals("所有端点均不可用（API Key 缺失或协议不支持）", ex.message)
    }

    @Test
    fun `chatSync 端点列表为空时抛未配置任何 LLM 端点`() {
        val registry = FakeRegistry(endpoints = emptyList(), clients = emptyMap())
        val engine = DefaultRulesetEngine(registry)
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.chatSync(emptyList(), ChatConfig()) }
        }
        assertEquals("未配置任何 LLM 端点，无法发起对话", ex.message)
    }

    @Test
    fun `chat 流式 emit 部分后中断不降级到下一端点`() {
        // 端点 a 先 emit "partial-" 后抛 IOException，不应降级到端点 b
        val registry = FakeRegistry(
            endpoints = listOf(snapshot("a", 0), snapshot("b", 1)),
            clients = mapOf(
                "a" to FakeClient("a", streamTokens = listOf("partial-"), streamError = IOException("stream truncated")),
                "b" to FakeClient("b", streamTokens = listOf("fallback-complete")),
            ),
        )
        val engine = DefaultRulesetEngine(registry)
        val tokens = mutableListOf<String>()
        val ex = assertThrows(IOException::class.java) {
            runBlocking {
                engine.chat(emptyList(), ChatConfig()).collect { tokens.add(it) }
            }
        }
        assertEquals("stream truncated", ex.message)
        // 应已收到端点 a 的部分 token；不应收到端点 b 的内容（避免跨模型拼接）
        assertEquals(listOf("partial-"), tokens)
    }

    @Test
    fun `chat 流式尚未 emit 时遭遇非持久性错误降级到下一端点`() {
        val registry = FakeRegistry(
            endpoints = listOf(snapshot("a", 0), snapshot("b", 1)),
            clients = mapOf(
                "a" to FakeClient("a", streamTokens = emptyList(), streamError = IOException("connect failed")),
                "b" to FakeClient("b", streamTokens = listOf("fallback-emit")),
            ),
        )
        val engine = DefaultRulesetEngine(registry)
        val tokens = runBlocking {
            engine.chat(emptyList(), ChatConfig()).toList()
        }
        assertEquals(listOf("fallback-emit"), tokens)
    }

    @Test
    fun `chat JSON mode prompt 降级追加 JSON_MODE_HINT 到 system 消息`() {
        val fakeClient = FakeClient("a", result = "captured")
        val registry = FakeRegistry(
            endpoints = listOf(snapshot("a", 0, capabilities = "TEXT")), // 未声明 JSON_MODE_NATIVE
            clients = mapOf("a" to fakeClient),
        )
        val engine = DefaultRulesetEngine(registry)
        val messages = listOf(
            ChatMessage(ChatMessage.Role.SYSTEM, "你是助手"),
            ChatMessage(ChatMessage.Role.USER, "ping"),
        )
        runBlocking {
            engine.chatSync(messages, ChatConfig(jsonMode = true))
        }
        val systemText = fakeClient.lastSyncMessages.first().textContent()
        assertTrue("system 应追加 JSON 约束指令", systemText.contains("请严格只输出合法 JSON 对象"))
        assertTrue("原 system 内容应保留", systemText.contains("你是助手"))
    }

    @Test
    fun `chat JSON mode 端点声明 JSON_MODE_NATIVE 时不追加 prompt 降级`() {
        val fakeClient = FakeClient("a", result = "captured")
        val registry = FakeRegistry(
            endpoints = listOf(snapshot("a", 0, capabilities = "TEXT,JSON_MODE_NATIVE")),
            clients = mapOf("a" to fakeClient),
        )
        val engine = DefaultRulesetEngine(registry)
        val messages = listOf(ChatMessage(ChatMessage.Role.SYSTEM, "你是助手"))
        runBlocking {
            engine.chatSync(messages, ChatConfig(jsonMode = true))
        }
        // 原生 JSON mode 不走 prompt 降级，system 不应被追加 JSON_MODE_HINT
        assertEquals("你是助手", fakeClient.lastSyncMessages.first().textContent())
    }

    @Test
    fun `ping 端点不存在返回 false`() {
        val registry = FakeRegistry(endpoints = emptyList(), clients = emptyMap())
        val engine = DefaultRulesetEngine(registry)
        val result = runBlocking { engine.ping("nonexistent") }
        assertFalse(result)
    }

    @Test
    fun `ping client 抛异常时向上传播不吞`() {
        val registry = FakeRegistry(
            endpoints = listOf(snapshot("a", 0)),
            clients = mapOf("a" to FakeClient("a", pingError = SdkServiceException(401))),
        )
        val engine = DefaultRulesetEngine(registry)
        assertThrows(SdkServiceException::class.java) {
            runBlocking { engine.ping("a") }
        }
    }

    @Test
    fun `CancellationException 不被吞保持协程取消语义`() {
        val cex = object : CancellationException("cancelled") {}
        val registry = FakeRegistry(
            endpoints = listOf(snapshot("a", 0)),
            clients = mapOf("a" to FakeClient("a", syncError = cex)),
        )
        val engine = DefaultRulesetEngine(registry)
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { engine.chatSync(emptyList(), ChatConfig()) }
        }
        assertEquals("cancelled", thrown.message)
    }

    // ---------- helpers ----------

    private fun snapshot(
        id: String,
        orderIndex: Int,
        capabilities: String = "TEXT",
        protocol: String = "openai_compatible",
    ) = EndpointSnapshot(
        id = id,
        displayName = "endpoint-$id",
        protocol = protocol,
        baseUrl = "https://example.com",
        model = "test-model",
        capabilities = capabilities,
        orderIndex = orderIndex,
    )

    /**
     * 模拟 OpenAI / Anthropic SDK 的 Service 异常基类（带 statusCode() 方法）。
     * 真实 SDK 中 com.openai.errors.OpenAIServiceException / com.anthropic.errors.AnthropicServiceException
     * 均有 `int statusCode()` 方法，DefaultRulesetEngine 通过反射读取。
     */
    private open class SdkServiceException(private val code: Int) : Exception("HTTP $code") {
        @Suppress("unused") // 由 DefaultRulesetEngine 通过反射调用
        fun statusCode(): Int = code
    }

    private class FakeRegistry(
        val endpoints: List<EndpointSnapshot>,
        val clients: Map<String, LlmClient>,
    ) : EndpointRegistry {
        override suspend fun getClient(endpointId: String): LlmClient? = clients[endpointId]
        override suspend fun getDefaultClient(): LlmClient? = clients[endpoints.firstOrNull()?.id]
        override suspend fun listEndpoints(): List<EndpointSnapshot> = endpoints
        override suspend fun getEndpoint(id: String): EndpointSnapshot? = endpoints.firstOrNull { it.id == id }
        override suspend fun invalidateCache() = Unit
        override suspend fun invalidate(endpointId: String) = Unit
    }

    private class FakeClient(
        override val endpointId: String,
        private val result: String = "",
        private val syncError: Throwable? = null,
        private val streamTokens: List<String> = emptyList(),
        private val streamError: Throwable? = null,
        private val pingError: Throwable? = null,
    ) : LlmClient {
        var lastSyncMessages: List<ChatMessage> = emptyList()
            private set

        override val capabilities: Set<ModelCapability> = emptySet()

        override suspend fun chat(messages: List<ChatMessage>, config: ChatConfig): Flow<String> = flow {
            for (token in streamTokens) {
                emit(token)
            }
            streamError?.let { throw it }
        }

        override suspend fun chatSync(messages: List<ChatMessage>, config: ChatConfig): String {
            lastSyncMessages = messages
            syncError?.let { throw it }
            return result
        }

        override suspend fun ping(): Boolean {
            pingError?.let { throw it }
            return true
        }
    }
}
