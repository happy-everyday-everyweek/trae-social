package com.trae.social.llm

/**
 * core-llm 拥有的端点元数据快照（#307：取代直接暴露 core-data 的
 * `LlmEndpointEntity` 持久化类型）。
 *
 * **设计动机**：`LlmEndpointEntity` 是 Room 持久化层类型，带 `@Entity` 注解与
 * Room 主键 / 索引等持久化关注点。原实现通过 [EndpointConfigProvider] /
 * [EndpointRegistry] 把该类型直接透传到 core-llm API 表面，导致：
 * - core-llm 上游模块（feature-onboarding / feature-profile / core-scheduler ...）
 *   间接依赖 core-data 的 Room schema，schema 变更引发跨模块编译耦合。
 * - 单元测试 core-llm 组件时必须构造 Room Entity 实例（或 mockk），与持久化层耦合。
 *
 * 引入 [EndpointSnapshot] 后，core-llm 公共 API 不再暴露任何 Room 类型，
 * 持久化 entity → snapshot 的映射在 [com.trae.social.app.di.AppEndpointConfigProvider]
 * （app 模块）完成，core-llm 内部测试可直接构造 snapshot，无需依赖 core-data。
 *
 * 字段集合 = [EndpointConfig.fromSnapshot] + [DefaultRulesetEngine.prepareMessages]
 * 当前所需的最小元数据；未来需要更多字段（如 createdAt）时按需扩展。
 *
 * @param id 端点 id（持久化主键）。
 * @param displayName UI 展示名。
 * @param protocol 协议格式 id（[com.trae.social.core.data.config.LlmProtocol.id] 字符串）。
 * @param baseUrl Base URL（已规范化）。
 * @param model 模型名。
 * @param capabilities 能力集合存储字符串（[com.trae.social.core.data.config.ModelCapability]
 *   逗号分隔），由消费方按需 parseSet。
 * @param orderIndex 全局排序，0 = 主模型。
 */
data class EndpointSnapshot(
    val id: String,
    val displayName: String,
    val protocol: String,
    val baseUrl: String,
    val model: String,
    val capabilities: String,
    val orderIndex: Int,
)
