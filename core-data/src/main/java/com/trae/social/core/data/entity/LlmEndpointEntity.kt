package com.trae.social.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * LLM 端点配置实体（#151 重构：取代按 [com.trae.social.core.data.config.LlmProvider]
 * 寻址的旧配置槽位）。
 *
 * 一个端点 = 协议格式 + API Key + Base URL + 模型名 + 能力声明。
 * 用户可配置多个端点，并通过 [orderIndex] 全局排序确定优先级链：
 * - **第 0 位** = 主模型（默认生成模型）。
 * - 排序链里第 1 个声明了某模态能力的端点 = 该模态的默认解析模型。
 *
 * **API Key 不入此表**（Room 不加密）：仍走 EncryptedSharedPreferences，
 * key 改为 `api_key_${endpointId}`（见 [com.trae.social.core.data.repository.ConfigRepository]）。
 *
 * @param id UUID，端点唯一标识。
 * @param displayName UI 展示名（如 "OpenAI 主端点"、"本地 Ollama"）。
 * @param protocol 协议格式 id（[com.trae.social.core.data.config.LlmProtocol.id]）。
 * @param baseUrl Base URL（必填，已规范化为带 scheme 与结尾 "/"）。
 * @param model 模型名（如 "gpt-4o-mini"、"claude-3-5-sonnet-20240620"）。
 * @param capabilities 能力集合的存储字符串（[com.trae.social.core.data.config.ModelCapability] 逗号分隔）。
 * @param orderIndex 全局排序，0 = 主模型。
 * @param createdAt 创建时间戳。
 * @param updatedAt 最近更新时间戳。
 */
@Entity(
    tableName = "llm_endpoints",
    indices = [
        Index(value = ["orderIndex"], unique = false),
        Index(value = ["protocol"], unique = false),
    ]
)
data class LlmEndpointEntity(
    @PrimaryKey
    val id: String,
    val displayName: String,
    val protocol: String,
    val baseUrl: String,
    val model: String,
    val capabilities: String,
    val orderIndex: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
