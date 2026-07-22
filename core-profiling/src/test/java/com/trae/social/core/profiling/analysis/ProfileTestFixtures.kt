package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import kotlinx.serialization.json.JsonElement

/**
 * 画像分析测试共享夹具（#292d）。
 *
 * 统一 [EventTextPreParserTest] 与 [BasicProfileAnalyzerTextFusionTest] 的 mkEvent 工厂，
 * 消除两处签名不兼容（一处带 id 参数，一处自动生成 id）的重复定义。
 */

/**
 * 构造测试用 [UserActionEvent]。
 *
 * @param type 行为类型。
 * @param targetId 目标 ID。
 * @param occurredAt 发生时间戳。
 * @param extra 扩展字段。
 * @param id 事件 ID，默认 "e1"。BasicProfileAnalyzer 不依赖事件 id，多事件共用默认值不影响断言；
 *   EventTextPreParser 按事件 id 写回解析结果，相关测试需显式传入唯一 id。
 */
fun mkEvent(
    type: UserActionType,
    targetId: String?,
    occurredAt: Long,
    extra: Map<String, JsonElement> = emptyMap(),
    id: String = "e1",
) = UserActionEvent(
    id = id,
    type = type,
    screen = "test",
    targetId = targetId,
    targetKind = "tweet",
    extra = extra,
    occurredAt = occurredAt,
    session = "session-test",
)
