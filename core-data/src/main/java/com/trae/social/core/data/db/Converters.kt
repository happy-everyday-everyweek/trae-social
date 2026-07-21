package com.trae.social.core.data.db

import androidx.room.TypeConverter
import com.trae.social.core.data.entity.InteractionType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room TypeConverters：处理 JSON 字段与枚举的持久化。
 *
 * 覆盖字段：
 * - AccountEntity.emojiPreference / catchphrase：List<String>
 * - AccountEntity.activeWindows：List<Boolean>
 * - PersonaDynamicFieldEntity.relationshipNetwork：List<String>
 * - InteractionEntity.type：InteractionType
 */
class Converters {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val stringListSerializer = ListSerializer(String.serializer())
    private val booleanListSerializer = ListSerializer(Boolean.serializer())

    @TypeConverter
    fun stringListToJson(value: List<String>?): String {
        if (value == null) return "[]"
        return json.encodeToString(stringListSerializer, value)
    }

    @TypeConverter
    fun jsonToStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        // #176：损坏 JSON 降级为空列表，避免 SerializationException 经 Room 生成代码
        // 向上传播导致整个查询崩溃（AccountEntity.emojiPreference/catchphrase 等依赖此 converter）
        return runCatching { json.decodeFromString(stringListSerializer, value) }
            .getOrDefault(emptyList())
    }

    @TypeConverter
    fun booleanListToJson(value: List<Boolean>?): String {
        if (value == null) return "[]"
        return json.encodeToString(booleanListSerializer, value)
    }

    @TypeConverter
    fun jsonToBooleanList(value: String?): List<Boolean> {
        if (value.isNullOrBlank()) return emptyList()
        // #176：同 jsonToStringList，损坏 JSON 降级为空列表而非抛异常
        return runCatching { json.decodeFromString(booleanListSerializer, value) }
            .getOrDefault(emptyList())
    }

    @TypeConverter
    fun interactionTypeToString(value: InteractionType?): String? {
        return value?.name
    }

    @TypeConverter
    fun stringToInteractionType(value: String?): InteractionType? {
        if (value.isNullOrBlank()) return null
        // #172：InteractionEntity.type 为非空字段，无效枚举值返回 null 会被 Room 注入非空字段
        // 触发 Intrinsics.checkNotNull NPE 导致整个查询崩溃。脏数据降级为 LIKE（首个枚举值）
        // 让该行可读而非整查询失败，与 jsonToStringList 的空列表降级策略对称。
        return runCatching { InteractionType.valueOf(value) }.getOrDefault(InteractionType.LIKE)
    }
}
