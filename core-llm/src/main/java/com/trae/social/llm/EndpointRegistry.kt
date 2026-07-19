package com.trae.social.llm

import com.trae.social.core.data.config.LlmProtocol
import com.trae.social.core.data.entity.LlmEndpointEntity
import com.trae.social.llm.anthropic.AnthropicCompatibleClient
import com.trae.social.llm.openai.OpenAiCompatibleClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 端点注册中心（#151：取代按 [com.trae.social.core.data.config.LlmProvider] 寻址的旧
 * [LlmProviderRegistry]）。
 *
 * - [getClient]：按 endpointId 懒创建并缓存 [LlmClient] 实例。首次调用从
 *   [EndpointConfigProvider] 读取端点配置 + API Key，组装 [EndpointConfig] 后构造对应协议
 *   的 SDK client（[OpenAiCompatibleClient] / [AnthropicCompatibleClient]）。
 * - [getDefaultClient]：返回 orderIndex=0 的端点对应 client（主模型）。
 * - [invalidateCache]：清空所有缓存的 client 实例。端点配置变更后调用，
 *   也可通过订阅 [EndpointConfigProvider.observeEndpointChanges] 自动触发。
 *
 * **并发安全**：[getClient] / [getDefaultClient] 为 suspend，使用 [Mutex] 替代
 * @Synchronized 保证线程安全；double-check 模式避免重复创建。
 *
 * 取代旧 [LlmProviderRegistry.invalidateCache]（按 provider 寻址）的失效语义：
 * - API Key 变更（端点 CRUD / API Key 保存）→ [ConfigRepository._endpointChanges] 发出
 *   → [EndpointRegistry] 收集 → [invalidateCache]
 * - 端点 reorder → 同上
 */
@Singleton
class EndpointRegistry @Inject constructor(
    private val configProvider: EndpointConfigProvider,
) {

    private val clients = ConcurrentHashMap<String, LlmClient>()
    private val mutex = Mutex()

    /**
     * 内部协程作用域，用于订阅端点变更流。
     *
     * SupervisorJob：单个子 job 失败不影响作用域其他子 job。
     * Dispatchers.IO：订阅与失效操作走 IO 线程，避免阻塞 UI。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 订阅端点变更：任何端点 CRUD / API Key 变更后失效缓存。
        // 主 review 第 1 轮 m-7 修复：原实现把 try/catch 包在 collect 外层，
        // 任何异常（如 ConfigRepository 内部 SharedFlow 异常）都会终止订阅协程，
        // 此后用户改 API Key / 增删端点缓存都不会失效，"改了 Key 但还在用旧的"。
        // 改为 while(isActive) + 内层 try/catch，异常后延迟 1s 重订阅。
        // 主 review 第 2 轮修复：catch (Throwable) 会吞 Error（OOM），改为 catch (Exception)
        // 让 Error 自然传播，避免 OOM 时还尝试 delay+重订阅加剧崩溃。
        scope.launch {
            while (isActive) {
                try {
                    configProvider.observeEndpointChanges().collect {
                        invalidateCache()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "EndpointRegistry 订阅端点变更失败，1s 后重试")
                    try {
                        delay(1000)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * 按 endpointId 获取 client。不存在则懒创建并缓存。
     *
     * 端点不存在 / **需鉴权协议的 API Key 缺失** 时返回 null（调用方应处理 null 情况）。
     *
     * **API Key 缺失快速失败（按协议区分）**：
     * - [LlmProtocol.requiresApiKey]=true（如 ANTHROPIC_COMPATIBLE）且 API Key 缺失时
     *   直接返回 null，避免发起注定 401 的网络请求浪费 RTT 与配额。
     * - [LlmProtocol.requiresApiKey]=false（如 OPENAI_COMPATIBLE）时即使 API Key 缺失
     *   也构造 client，让 SDK 自行处理空 Key（跳过 Authorization 头）。
     *   这覆盖了本地 Ollama 等无需鉴权的 OpenAI 兼容端点场景（#271 review Major 3）。
     */
    suspend fun getClient(endpointId: String): LlmClient? {
        clients[endpointId]?.let { return it }
        return mutex.withLock {
            // Double-check after acquiring lock
            clients[endpointId]?.let { return it }
            val entity = configProvider.getEndpoint(endpointId) ?: return null
            // 主 review 第 2 轮修复：原 runCatching 会吞 CancellationException，协程取消被
            // 误判为 API Key 读取失败返回 null。改为 try/catch 显式重抛 CancellationException。
            // 主 review 第 3 轮修复：catch (Throwable) 会吞 Error（OOM / StackOverflow），
            // 与同文件订阅处（init 块）的"Error 自然传播"策略一致，改为 catch (Exception)。
            val apiKey = try {
                configProvider.getEndpointApiKey(endpointId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "EndpointRegistry.getEndpointApiKey 失败 endpointId=%s", endpointId)
                null
            }
            // API Key 缺失快速失败（按协议区分）：需鉴权协议 + Key 缺失 → 跳过 client 创建，
            // 避免发起注定 401 的网络请求浪费 RTT 与配额。
            // 无需鉴权协议（如本地 Ollama）即使 Key 缺失也构造 client（#271 review Major 3）。
            val protocol = LlmProtocol.fromId(entity.protocol)
            if (protocol?.requiresApiKey == true && apiKey.isNullOrBlank()) {
                Timber.w("EndpointRegistry 端点 API Key 缺失，跳过 client 创建 endpointId=%s protocol=%s", endpointId, protocol.id)
                return null
            }
            val config = EndpointConfig.fromEntity(entity, apiKey) ?: return null
            val client = createClient(config) ?: return null
            clients[endpointId] = client
            client
        }
    }

    /**
     * 获取主模型 client（orderIndex=0）。
     *
     * 端点列表为空时返回 null（调用方应处理：通常引导用户配置端点）。
     */
    suspend fun getDefaultClient(): LlmClient? {
        // 主 review 第 2 轮修复：原 runCatching 会吞 CancellationException。
        // 主 review 第 3 轮修复：catch (Throwable) 改为 catch (Exception) 让 Error 自然传播。
        val endpoints = try {
            configProvider.listEndpoints()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "EndpointRegistry.getDefaultClient: listEndpoints 失败")
            return null
        }
        val primary = endpoints.firstOrNull() ?: return null
        return getClient(primary.id)
    }

    /**
     * 列出所有端点（按 orderIndex 升序）。供 UI / 引擎选择降级链使用。
     */
    suspend fun listEndpoints(): List<LlmEndpointEntity> {
        // 主 review 第 2 轮修复：原 runCatching 会吞 CancellationException。
        // 主 review 第 3 轮修复：catch (Throwable) 改为 catch (Exception) 让 Error 自然传播。
        return try {
            configProvider.listEndpoints()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "EndpointRegistry.listEndpoints 失败")
            emptyList()
        }
    }

    /**
     * 按 id 获取端点（不走缓存，每次读 Room）。
     */
    suspend fun getEndpoint(id: String): LlmEndpointEntity? {
        // 主 review 第 2 轮修复：原 runCatching 会吞 CancellationException。
        // 主 review 第 3 轮修复：catch (Throwable) 改为 catch (Exception) 让 Error 自然传播。
        return try {
            configProvider.getEndpoint(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "EndpointRegistry.getEndpoint 失败 id=%s", id)
            null
        }
    }

    /**
     * 清空所有缓存的 client 实例。
     *
     * 在以下场景应调用（或通过订阅 [EndpointConfigProvider.observeEndpointChanges] 自动触发）：
     * - 用户增删改端点
     * - API Key 变更
     * - 端点 reorder
     */
    suspend fun invalidateCache() {
        mutex.withLock { clients.clear() }
    }

    /**
     * 按 endpointId 失效单个 client（保留其他）。
     */
    suspend fun invalidate(endpointId: String) {
        mutex.withLock { clients.remove(endpointId) }
    }

    /**
     * 根据协议格式构造对应 SDK client。
     *
     * 主 review 第 1 轮 m-6 修复：原实现 `when (config.protocol) { ... } ?: return null`
     * 是死代码——`when` 对 `LlmProtocol` 枚举（当前只有 2 个值）exhaustive，
     * 编译器强制覆盖所有分支，`?: return null` 永远不会触发。改用 `else -> null`
     * 非 exhaustive 形式，让未来新增协议枚举值时真的能走到 null 分支（前向兼容语义）。
     */
    private fun createClient(config: EndpointConfig): LlmClient? = when (config.protocol) {
        LlmProtocol.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(config)
        LlmProtocol.ANTHROPIC_COMPATIBLE -> AnthropicCompatibleClient(config)
        // 未来新增协议枚举值时，编译器不会强制此 when 报错（非 exhaustive），
        // 走 else 分支返回 null，由调用方（getClient）跳过该端点。
        // 注意：新增协议实现后应在此处显式添加分支，避免静默 fallback。
        else -> null
    }
}
