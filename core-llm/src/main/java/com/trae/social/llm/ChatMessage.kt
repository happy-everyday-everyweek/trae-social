package com.trae.social.llm

import com.trae.social.core.data.config.ModelCapability

/**
 * 对话消息单元（#151 重构：多模态扩展）。
 *
 * [content] 从 `String` 扩展为 [ContentPart] 列表，可承载文本 / 图像 / 音频 / 视频块，
 * 为规则集引擎的多模态预处理与能力判断提供输入。
 *
 * 上层现有调用方均为纯文本，可用便捷构造函数 `ChatMessage(role, text)` 平滑迁移，
 * 无破坏性改动。
 *
 * @param role 消息角色（SYSTEM / USER / ASSISTANT）。
 * @param content 多模态内容块列表。空列表等价于无内容（一般不应出现）。
 */
data class ChatMessage(
    val role: Role,
    val content: List<ContentPart>,
) {
    enum class Role { SYSTEM, USER, ASSISTANT }

    /** 纯文本便捷构造（上层 90%+ 调用场景）。 */
    constructor(role: Role, text: String) : this(role, listOf(ContentPart.Text(text)))

    /** 取首个文本块的文本，没有则返回空串（兼容老调用方的 String 行为）。 */
    fun textContent(): String = content.firstNotNullOfOrNull { (it as? ContentPart.Text)?.text } ?: ""

    /** 该消息是否含非文本的多模态块。 */
    fun hasMultimodalContent(): Boolean = content.any { it !is ContentPart.Text }
}

/**
 * 多模态内容块。
 *
 * 规则集引擎据此判断请求是否含多模态内容，并选支持对应模态的端点预处理。
 */
sealed interface ContentPart {
    /** 文本块。 */
    data class Text(val text: String) : ContentPart

    /**
     * 图像块（端点须声明 [ModelCapability.VISION_INPUT] 才能直接处理）。
     * @param url 图像 URL（http/https 或 data URI）。
     * @param mimeType MIME 类型，如 "image/png"、"image/jpeg"。
     */
    data class Image(val url: String, val mimeType: String) : ContentPart

    /**
     * 音频块（端点须声明 [ModelCapability.AUDIO_INPUT]）。
     */
    data class Audio(val url: String, val mimeType: String) : ContentPart

    /**
     * 视频块（端点须声明 [ModelCapability.VIDEO_INPUT]）。
     */
    data class Video(val url: String, val mimeType: String) : ContentPart
}

/**
 * 对话生成参数。
 *
 * @param temperature 采样温度，越高越发散。
 * @param maxTokens 单次响应最大 token 数。
 * @param jsonMode 是否要求严格 JSON 输出。端点能力声明 [ModelCapability.JSON_MODE_NATIVE]
 *   时走原生 `response_format`，否则走 prompt 降级（在 system prompt 追加约束指令）。
 */
data class ChatConfig(
    val temperature: Float = 0.8f,
    val maxTokens: Int = 512,
    val jsonMode: Boolean = false,
)
