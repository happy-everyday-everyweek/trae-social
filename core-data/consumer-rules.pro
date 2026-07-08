# core-data 消费者 ProGuard 规则（IMPL-40）
# 被 app 模块消费，确保 release 构建时 Room / Serialization 相关类不被混淆。

# Room: 保留 Entity / Dao / RoomDatabase 子类及 TypeConverters
-keep class com.trae.social.core.data.entity.** { *; }
-keep class com.trae.social.core.data.dao.** { *; }
-keep class com.trae.social.core.data.db.** { *; }

# kotlinx.serialization: 保留 @Serializable DTO（PersonaDto, HistoricalTweetDto）
-keep @kotlinx.serialization.Serializable class com.trae.social.core.data.** { *; }
-keepclassmembers class com.trae.social.core.data.**$$serializer { *; }
