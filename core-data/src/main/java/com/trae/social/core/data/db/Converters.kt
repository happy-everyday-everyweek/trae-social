package com.trae.social.core.data.db

import androidx.room.TypeConverter
import com.trae.social.core.data.entity.InteractionType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber

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
        // 主 review 第 4 轮修复：InteractionEntity.type 为非空字段（val type: InteractionType），
        // 任何返回 null 的路径（包括 value 为 null/blank）都会被 Room 注入非空字段时触发
        // Intrinsics.checkNotNull NPE，导致整个查询崩溃（同一行其他字段也无法读取）。
        // 脏数据（空串 / 未知枚举名）统一降级为 LIKE（首个枚举值），让该行可读而非整查询失败，
        // 与 jsonToStringList / jsonToBooleanList 的空列表降级策略对称。
        //
        // review 第 5 轮修复：默认成 LIKE 会让损坏的 COMMENT/RETWEET/FOLLOW 记录被当作 LIKE
        // 读回，污染点赞计数且原始互动类型永久丢失（静默腐蚀）。此处降级时打一条 warn 日志，
        // 让脏数据可观测，便于在排查 likeCount 异常时定位。不引入新枚举值以避免改动
        // executeInteractionsAndUpdateTweet 的 when 分支与既有数据迁移。
        if (value.isNullOrBlank()) {
            Timber.w("InteractionType 读取到空值，降级为 LIKE")
            return InteractionType.LIKE
        }
        return runCatching { InteractionType.valueOf(value) }.getOrElse {
            Timber.w("InteractionType 读取到未知枚举名 %s，降级为 LIKE", value)
            InteractionType.LIKE
        }
    }
}
