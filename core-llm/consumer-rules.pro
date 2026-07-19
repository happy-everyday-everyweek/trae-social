# core-llm 消费者 ProGuard 规则（IMPL-40 / #151 重构后）
# 被 app 模块消费。#151 重构后旧手写 Retrofit API / OkHttp 拦截器已移除，
# 网络层由 OpenAI / Anthropic 官方 Java SDK 接管（各自 SDK 内部已带 keep 规则）。
# 本模块仍保留的 @Serializable DTO（如 ChatMessage / EndpointConfig）需要保护。

# kotlinx.serialization: 保留 @Serializable DTO
-keep @kotlinx.serialization.Serializable class com.trae.social.llm.** { *; }
-keepclassmembers class com.trae.social.llm.**$$serializer { *; }

# OpenAI / Anthropic SDK 异常类：本模块用反射 getMethod("statusCode") 读取状态码做
# 持久性错误 / 429 分类（DefaultRulesetEngine / OpenAiCompatibleClient /
# AnthropicCompatibleClient / OnboardingViewModel.extractSdkStatusCode）。
# OpenAI Java SDK 4.43.0 / Anthropic Java SDK 2.34.1 不携带 consumer-rules.pro，
# 且 statusCode() 是抽象方法被子类（BadRequestException / UnauthorizedException /
# RateLimitException 等）override ——R8 minify 会重命名方法导致反射失效。
# 主 review 第 1 轮 B-1：必须 keep 整个 errors 子包，反射才能稳定命中。
-keep class com.openai.errors.** { *; }
-keep class com.anthropic.errors.** { *; }
