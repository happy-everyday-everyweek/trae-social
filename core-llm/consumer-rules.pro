# core-llm 消费者 ProGuard 规则（IMPL-40）
# 被 app 模块消费，确保 release 构建时 Retrofit 接口 / Serialization DTO / 拦截器不被混淆。

# Retrofit: 保留 API 接口方法签名（@retrofit2.http.* 注解方法）
-keep,allowobfuscation,allowshrinking @retrofit2.http.* interface com.trae.social.llm.** { *; }

# kotlinx.serialization: 保留 @Serializable DTO（请求/响应体）
-keep @kotlinx.serialization.Serializable class com.trae.social.llm.** { *; }
-keepclassmembers class com.trae.social.llm.**$$serializer { *; }

# OkHttp 拦截器
-keep class com.trae.social.llm.interceptor.** { *; }
