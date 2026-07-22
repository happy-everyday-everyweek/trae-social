package com.trae.social.llm

import com.trae.social.core.data.config.LlmProtocol
import com.trae.social.core.data.config.ModelCapability
import com.trae.social.core.data.entity.LlmEndpointEntity
import timber.log.Timber

/**
 * 端点配置的运行时视图（#151）。
 *
 * 由 [com.trae.social.llm.EndpointRegistry] 从 [LlmEndpointEntity] + API Key 组装，
 * 供规则集引擎与各 client 实现读取。
 *
 * API Key 由 registry 在创建时一次性从 [com.trae.social.llm.EndpointConfigProvider]
 * 读取注入，避免每次请求都跑 EncryptedSharedPreferences。
 */
data class EndpointConfig(
    /** 端点 id（持久化主键）。 */
    val id: String,
    /** UI 展示名。 */
    val displayName: String,
    /** 协议格式。 */
    val protocol: LlmProtocol,
    /** Base URL（已规范化）。 */
    val baseUrl: String,
    /** 模型名。 */
    val model: String,
    /** 能力集合。 */
    val capabilities: Set<ModelCapability>,
    /** API Key（运行时注入，不持久化于 Room）。 */
    val apiKey: String?,
    /** 全局排序，0 = 主模型。 */
    val orderIndex: Int,
) {
    /** 该端点是否支持给定能力。 */
    fun supports(capability: ModelCapability): Boolean = capability in capabilities

    /** 该端点是否可靠支持流式。 */
    val supportsStreaming: Boolean get() = ModelCapability.STREAMING in capabilities

    /**
     * #305 修复：data class 默认 toString 会输出 [apiKey] 明文，若实例被 Timber/logcat
     * 记录（错误日志、调试输出、异常 message）则密钥泄漏到设备日志，可被 root 应用或
     * bug-report 导出读取。重写为脱敏形式。
     */
    override fun toString(): String {
        val maskedKey = when {
            apiKey.isNullOrBlank() -> "<none>"
            apiKey.length <= 8 -> "***"
            else -> apiKey.take(4) + "***" + apiKey.takeLast(4)
        }
        return "EndpointConfig(id=$id, displayName=$displayName, protocol=$protocol, " +
            "baseUrl=$baseUrl, model=$model, capabilities=$capabilities, " +
            "apiKey=$maskedKey, orderIndex=$orderIndex)"
    }

    /**
     * #305：自定义 equals/hashCode，避免 data class 默认实现把 [apiKey] 纳入比较导致
     * 密钥进入哈希计算/日志的概率极低但无意义。仍按全部字段相等（含 apiKey）判定，
     * 保证语义与 data class 一致，但与 [toString] 解耦。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EndpointConfig) return false
        return id == other.id && displayName == other.displayName && protocol == other.protocol &&
            baseUrl == other.baseUrl && model == other.model && capabilities == other.capabilities &&
            apiKey == other.apiKey && orderIndex == other.orderIndex
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + protocol.hashCode()
        result = 31 * result + baseUrl.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + (apiKey?.hashCode() ?: 0)
        result = 31 * result + orderIndex
        return result
    }

    companion object {
        /**
         * 从持久化 entity + API Key 构造运行时配置。
         *
         * 主 review 第 2 轮修复：原实现对未知 protocol id 静默 fallback 到 [LlmProtocol.OPENAI_COMPATIBLE]，
         * 与 m-6 修复（createClient 的 else 分支返回 null）语义冲突——上游 fallback 后 else 分支永远
         * 不可达，相当于死代码。改为返回 null，由 [EndpointRegistry.getClient] 跳过该端点
         * （与 m-2 的 anySkipped 语义对齐），避免用 OpenAI SDK 调用非 OpenAI 端点引发难定位错误。
         */
        fun fromEntity(entity: LlmEndpointEntity, apiKey: String?): EndpointConfig? {
            val protocol = LlmProtocol.fromId(entity.protocol)
                ?: run {
                    Timber.w("EndpointConfig.fromEntity 未知 protocol=%s，跳过端点 endpointId=%s", entity.protocol, entity.id)
                    return null
                }
            return EndpointConfig(
                id = entity.id,
                displayName = entity.displayName,
                protocol = protocol,
                baseUrl = entity.baseUrl,
                model = entity.model,
                capabilities = ModelCapability.parseSet(entity.capabilities),
                apiKey = apiKey,
                orderIndex = entity.orderIndex,
            )
        }
    }
}
