# core-llm 消费者 ProGuard 规则（IMPL-40 / #151 重构后）
# 被 app 模块消费。#151 重构后旧手写 Retrofit API / OkHttp 拦截器已移除，
# 网络层由 OpenAI / Anthropic 官方 Java SDK 接管（各自 SDK 内部已带 keep 规则）。
# 本模块仍保留的 @Serializable DTO（如 ChatMessage / EndpointConfig）需要保护。

# kotlinx.serialization: 保留 @Serializable DTO
-keep @kotlinx.serialization.Serializable class com.trae.social.llm.** { *; }
-keepclassmembers class com.trae.social.llm.**$$serializer { *; }
