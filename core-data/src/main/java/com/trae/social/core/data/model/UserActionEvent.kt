package com.trae.social.core.data.model

import kotlinx.serialization.Serializable

/**
 * 用户行为事件领域模型（捕获层在内存中传递，落库时映射为 UserActionEventEntity）。
 *
 * @param extra 版本化 JSON 上下文（schemaVer / drivenByProfile / scenarioId / group /
 *   imageTheme / captionLen / imageCount / fromTab 等），由调用方按需构造。
 */
@Serializable
data class UserActionEvent(
    val id: String,
    val type: UserActionType,
    val screen: String,
    val targetId: String? = null,
    val targetKind: String? = null,
    val extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val durationMs: Long? = null,
    val occurredAt: Long,
    val session: String
)
