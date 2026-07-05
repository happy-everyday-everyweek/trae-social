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
        return json.decodeFromString(stringListSerializer, value)
    }

    @TypeConverter
    fun booleanListToJson(value: List<Boolean>?): String {
        if (value == null) return "[]"
        return json.encodeToString(booleanListSerializer, value)
    }

    @TypeConverter
    fun jsonToBooleanList(value: String?): List<Boolean> {
        if (value.isNullOrBlank()) return emptyList()
        return json.decodeFromString(booleanListSerializer, value)
    }

    @TypeConverter
    fun interactionTypeToString(value: InteractionType?): String? {
        return value?.name
    }

    @TypeConverter
    fun stringToInteractionType(value: String?): InteractionType? {
        if (value.isNullOrBlank()) return null
        return runCatching { InteractionType.valueOf(value) }.getOrNull()
    }
}
