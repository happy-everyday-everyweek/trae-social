package com.trae.social.core.data.config

/**
 * 模型能力声明（#151 重构核心）。
 *
 * 每个端点配置声明其支持的能力集合；默认规则集据此判断是否需要多模态预处理、
 * 选哪个端点做预处理、降级时在哪些端点间切换。
 *
 * - [TEXT]：基础文本生成 / 对话。所有模型必声明。
 * - [JSON_MODE_NATIVE]：原生结构化输出（OpenAI `response_format=json_object` /
 *   Gemini `responseMimeType=application/json`）。声明该能力才用原生方式请求 JSON，
 *   否则走 prompt 降级（在 system prompt 中追加 JSON 约束指令）。
 * - [VISION_INPUT]：图像理解（识图 / 视觉）。
 * - [IMAGE_GENERATION]：文生图 / 图生图。
 * - [AUDIO_INPUT] / [AUDIO_OUTPUT]：语音理解 / TTS。
 * - [VIDEO_INPUT]：视频输入理解。
 * - [STREAMING]：是否可靠支持流式输出（部分端点的 SSE 路径不稳定，可关闭流式走同步）。
 */
enum class ModelCapability {
    TEXT,
    JSON_MODE_NATIVE,
    VISION_INPUT,
    IMAGE_GENERATION,
    AUDIO_INPUT,
    AUDIO_OUTPUT,
    VIDEO_INPUT,
    STREAMING,
    ;

    companion object {
        /**
         * 从 Room 持久化的逗号分隔字符串解析能力集合。
         */
        fun parseSet(stored: String?): Set<ModelCapability> {
            if (stored.isNullOrBlank()) return emptySet()
            return stored.split(",")
                .mapNotNull { token -> runCatching { valueOf(token.trim().uppercase()) }.getOrNull() }
                .toSet()
        }

        /**
         * 把能力集合序列化为逗号分隔字符串，便于 Room 持久化。
         */
        fun Set<ModelCapability>.toStorageString(): String =
            joinToString(",") { it.name }
    }
}
