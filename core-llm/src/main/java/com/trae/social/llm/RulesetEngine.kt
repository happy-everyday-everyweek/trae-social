package com.trae.social.llm

import kotlinx.coroutines.flow.Flow

/**
 * 规则集引擎（#151 重构核心：取代旧 [LlmClient] 对外抽象）。
 *
 * 上层调用方仅与该接口交互，提供极简三种语义：
 * - [chat] 流式对话
 * - [chatSync] 非流式对话
 * - [ping] 连通性测试
 *
 * 调用方可选声明使用某个自定义规则集 ID（开发者代码注册），不传走默认规则集。
 *
 * 默认规则集实现：多端点排序 + 多模态预处理管道 + 静默降级 + 流式中断重生成。
 */
interface RulesetEngine {

    /**
     * 流式对话：逐 token 返回增量文本。
     *
     * 流式 emit 部分后中断时，引擎丢弃已 emit 内容并完全重新生成，
     * 不走降级链拼接跨模型内容（避免内容混乱）。
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        config: ChatConfig,
        rulesetId: String? = null,
    ): Flow<String>

    /**
     * 非流式对话：阻塞等待完整响应后一次性返回。
     *
     * 默认规则集走主模型降级链（重试 1 次 → 降到下一位端点 → ... 直到链尾）。
     */
    suspend fun chatSync(
        messages: List<ChatMessage>,
        config: ChatConfig,
        rulesetId: String? = null,
    ): String

    /**
     * 连通性测试：向指定端点发送 ping，期望非空响应即视为成功。
     */
    suspend fun ping(endpointId: String): Boolean
}

/**
 * 自定义规则集注册表。
 *
 * 由**开发者在代码里实现并注册**（定义 [Ruleset] 接口 + 注册表），运行时调用方按标识 ID 选用。
 * 用户**不可在 UI 编辑**规则集（UI 只负责端点配置、能力声明与排序）。
 */
interface Ruleset {
    /** 规则集 id，调用方通过 [RulesetEngine.chat] 的 rulesetId 参数指定。 */
    val id: String
}

object RulesetRegistry {
    private val rulesets = mutableMapOf<String, Ruleset>()

    fun register(ruleset: Ruleset) {
        rulesets[ruleset.id] = ruleset
    }

    fun get(id: String): Ruleset? = rulesets[id]

    fun list(): List<Ruleset> = rulesets.values.toList()
}
