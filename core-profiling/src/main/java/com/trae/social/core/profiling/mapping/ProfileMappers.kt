package com.trae.social.core.profiling.mapping

import com.trae.social.core.data.entity.UserActionEventEntity
import com.trae.social.core.data.entity.UserProfileFeedbackEntity
import com.trae.social.core.data.entity.UserProfileOverrideEntity
import com.trae.social.core.data.entity.UserProfileRollbackEntity
import com.trae.social.core.data.entity.UserProfileSnapshotEntity
import com.trae.social.core.data.entity.UserProfileVersionEntity
import com.trae.social.core.data.model.FeedbackMessageSummary
import com.trae.social.core.data.model.FeedbackWeights
import com.trae.social.core.data.model.OverrideRecord
import com.trae.social.core.data.model.OverrideType
import com.trae.social.core.data.model.RollbackRecord
import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.data.model.UserFeedbackSummary
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.data.model.UserProfileVersion
import com.trae.social.core.data.model.VersionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 领域模型与 Room Entity 之间的双向映射（#146）。
 *
 * extra / payload / value 等 JSON 字段统一通过共享 [Json] 实例编解码。
 */
object ProfileMappers {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    // ---- UserActionEvent ----

    fun UserActionEvent.toEntity(): UserActionEventEntity = UserActionEventEntity(
        id = id,
        type = type.name,
        screen = screen,
        targetId = targetId,
        targetKind = targetKind,
        extra = if (extra.isEmpty()) null else json.encodeToString(JsonObject.serializer(), buildJsonObject {
            extra.forEach { (k, v) -> put(k, v) }
        }),
        durationMs = durationMs,
        occurredAt = occurredAt,
        session = session
    )

    fun UserActionEventEntity.toDomain(): UserActionEvent? {
        val type = UserActionType.fromName(this.type) ?: return null
        val extraMap: Map<String, JsonElement> = if (extra.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching {
                json.decodeFromString(JsonObject.serializer(), extra).toMap()
            }.getOrDefault(emptyMap())
        }
        return UserActionEvent(
            id = id,
            type = type,
            screen = screen,
            targetId = targetId,
            targetKind = targetKind,
            extra = extraMap,
            durationMs = durationMs,
            occurredAt = occurredAt,
            session = session
        )
    }

    // ---- UserProfileSnapshot ----

    fun UserProfileSnapshot.toEntity(source: String): UserProfileSnapshotEntity = UserProfileSnapshotEntity(
        payload = json.encodeToString(UserProfileSnapshot.serializer(), this),
        eventWindowStart = eventWindowStart,
        eventWindowEnd = eventWindowEnd,
        computedAt = computedAt,
        source = source
    )

    fun UserProfileSnapshotEntity.toDomain(): UserProfileSnapshot? =
        runCatching { json.decodeFromString(UserProfileSnapshot.serializer(), payload) }.getOrNull()

    // ---- UserProfileVersion ----

    fun UserProfileVersion.toEntity(): UserProfileVersionEntity = UserProfileVersionEntity(
        id = id,
        payload = json.encodeToString(UserProfileVersion.serializer(), this),
        narrative = narrative,
        modelProvider = modelProvider,
        promptHash = promptHash,
        inputFingerprint = inputFingerprint,
        snapshotId = snapshotId,
        rollbackFrom = rollbackFrom,
        isActive = isActive,
        createdAt = createdAt
    )

    fun UserProfileVersionEntity.toDomain(): UserProfileVersion? =
        runCatching { json.decodeFromString(UserProfileVersion.serializer(), payload) }.getOrNull()

    fun UserProfileVersionEntity.toSummary(): VersionSummary = VersionSummary(
        id = id,
        createdAt = createdAt,
        narrativePreview = narrative.take(120),
        isActive = isActive
    )

    // ---- Override ----

    fun UserProfileOverrideEntity.toDomain(): OverrideRecord? {
        val type = OverrideType.fromId(type) ?: return null
        return OverrideRecord(
            id = id,
            type = type,
            key = key,
            value = value,
            reason = reason,
            createdAt = createdAt,
            source = source,
            superseded = superseded
        )
    }

    fun OverrideRecord.toEntity(): UserProfileOverrideEntity = UserProfileOverrideEntity(
        id = id,
        type = type.id,
        key = key,
        value = value,
        reason = reason,
        createdAt = createdAt,
        source = source,
        superseded = superseded
    )

    // ---- Feedback ----

    fun UserProfileFeedbackEntity.toSummary(): FeedbackMessageSummary = FeedbackMessageSummary(
        role = role,
        content = content,
        createdAt = createdAt
    )

    fun List<UserProfileFeedbackEntity>.toUserFeedbackSummary(overrides: List<OverrideRecord>): UserFeedbackSummary =
        UserFeedbackSummary(
            recentMessages = map { it.toSummary() },
            activeOverrides = overrides
        )

    // ---- Rollback ----

    fun UserProfileRollbackEntity.toDomain(): RollbackRecord = RollbackRecord(
        id = id,
        fromVersionId = fromVersionId,
        toVersionId = toVersionId,
        reason = reason,
        appliedAt = appliedAt
    )

    /** 读取 extra 中的字符串字段（容错）。 */
    fun readExtraString(extra: Map<String, JsonElement>, key: String): String? =
        (extra[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    /** 读取 extra 中的布尔字段（容错）。 */
    fun readExtraBoolean(extra: Map<String, JsonElement>, key: String, default: Boolean = false): Boolean =
        (extra[key] as? JsonPrimitive)?.let {
            runCatching { it.content.toBooleanStrict() }.getOrDefault(default)
        } ?: default

    /** 读取 extra 中的整数字段（容错）。 */
    fun readExtraInt(extra: Map<String, JsonElement>, key: String): Int? =
        (extra[key] as? JsonPrimitive)?.let {
            runCatching { it.content.toInt() }.getOrNull()
        }

    /** 序列化 FeedbackWeights（用于覆盖 value 等）。 */
    fun encodeWeights(weights: FeedbackWeights): String =
        json.encodeToString(FeedbackWeights.serializer(), weights)

    /** 反序列化 FeedbackWeights（容错）。 */
    fun decodeWeights(value: String): FeedbackWeights? =
        runCatching { json.decodeFromString(FeedbackWeights.serializer(), value) }.getOrNull()
}
