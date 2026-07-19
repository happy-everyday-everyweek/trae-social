# core-llm LLM 层

LLM 抽象层，namespace `com.trae.social.llm`。通过 `api(project(":core-data"))` 复用 `LlmProtocol` / `ModelCapability` / `LlmEndpointEntity` 等持久化结构。

> **#151 重构总览**：把"按 `LlmProvider` 单槽位寻址 + 手写 Retrofit/SSE 解析"改为
> **"多端点配置 + 全局排序 + 降级链 + 官方 Java SDK"**。
> - 用 OpenAI Java SDK 4.43.0 / Anthropic Java SDK 2.34.1 取代手写 Retrofit + Interceptor 链
> - 抽象分层：`LlmClient`（单端点）/ `EndpointRegistry`（端点缓存 + 创建）/ `RulesetEngine`（降级链 + JSON mode prompt 降级）
> - 上层调用方只与 `RulesetEngine` 交互，不再直接持有 `LlmClient`
> - Room v7→v8 迁移：新增 `llm_endpoints` 表持久化多端点配置（API Key 仍走 EncryptedSharedPreferences）
> - 旧 14 个实现文件（OpenAiClient / AnthropicClient / GeminiClient / AuthInterceptor / RetryInterceptor / LoggingInterceptor / LlmProviderRegistry 等）已删除

## 目录结构

```
com.trae.social.llm/
├── LlmClient.kt              # 单端点客户端接口（引擎内部适配器）
├── ChatMessage.kt            # 消息 + 多模态 ContentPart + ChatConfig
├── EndpointConfig.kt         # 端点配置运行时视图
├── EndpointConfigProvider.kt # 端点配置提供者抽象（app 实现）
├── EndpointRegistry.kt       # 端点缓存 + 懒创建 + 订阅失效
├── RulesetEngine.kt          # 规则集引擎接口 + RulesetRegistry
├── DefaultRulesetEngine.kt   # 默认规则集实现（降级链 + JSON mode 降级）
├── openai/
│   └── OpenAiCompatibleClient.kt
├── anthropic/
│   └── AnthropicCompatibleClient.kt
├── interceptor/
│   └── RateLimitedException.kt   # 仅保留此异常类，旧 Interceptor 已删
├── ratelimit/
│   └── RateLimiter.kt            # 令牌桶（仍由 core-scheduler 调度入口使用）
├── prompt/                        # Prompt 构建（与旧版相同）
└── di/
    └── LlmModule.kt               # Hilt 绑定
```

## 三层抽象

### LlmClient（单端点）

```kotlin
interface LlmClient {
    suspend fun chat(messages: List<ChatMessage>, config: ChatConfig): Flow<String>
    suspend fun chatSync(messages: List<ChatMessage>, config: ChatConfig): String
    suspend fun ping(): Boolean
    val endpointId: String
    val capabilities: Set<ModelCapability>
}
```

- 单端点 SDK 包装，由 `OpenAiCompatibleClient` / `AnthropicCompatibleClient` 实现。
- 不再持有 `provider: LlmProvider`，改以 `endpointId` 寻址。
- **上层不应直接使用**，应通过 `RulesetEngine` 间接调用。

### EndpointRegistry（端点缓存）

```kotlin
@Singleton
class EndpointRegistry @Inject constructor(
    private val configProvider: EndpointConfigProvider,
) {
    suspend fun getClient(endpointId: String): LlmClient?
    suspend fun getDefaultClient(): LlmClient?      // orderIndex=0 的端点
    suspend fun listEndpoints(): List<LlmEndpointEntity>
    suspend fun invalidateCache()                    // 清空所有缓存
    suspend fun invalidate(endpointId: String)       // 失效单个
}
```

- `ConcurrentHashMap<String, LlmClient>` + `Mutex` 双重检查懒创建。
- **API Key 缺失快速失败**：`getClient` 在端点未配置 API Key 时直接返回 null，
  不构造 SDK client，避免发起注定 401 的请求浪费 RTT 与配额（与旧 AuthInterceptor 一致）。
- **自动失效缓存**：`init` 中订阅 `EndpointConfigProvider.observeEndpointChanges()`，
  任何端点 CRUD / reorder / API Key 变更后清空缓存重建。
- 内部 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 为 `@Singleton` 进程级作用域。

### RulesetEngine（对外抽象）

```kotlin
interface RulesetEngine {
    suspend fun chat(messages: List<ChatMessage>, config: ChatConfig, rulesetId: String? = null): Flow<String>
    suspend fun chatSync(messages: List<ChatMessage>, config: ChatConfig, rulesetId: String? = null): String
    suspend fun ping(endpointId: String): Boolean
}
```

- 上层调用方（Worker / FeedbackAgent / EventTextPreParser / OnboardingViewModel）的**唯一入口**。
- `rulesetId` 当前未接入自定义规则集（保留扩展点）。
- `ping` 异常**向上抛出**而非吞掉——调用方用 `runCatching` 捕获后可分类 SDK 异常给出
  具体错误原因（401 / 403 / 5xx / 网络错误等）。

## DefaultRulesetEngine 行为

`@Singleton class @Inject constructor(registry: EndpointRegistry) : RulesetEngine`

### 1. 主模型降级链（chatSync）

`chatSync` 在主端点失败后，按 `orderIndex` 依次尝试降级链上的下一个端点。

- **持久性错误（4xx 非 429）不降级**，直接抛出。
- **429 兼容**：SDK 抛出的 429 异常被转换为 `RateLimitedException` 向上传递，
  使各 Worker 既有 `catch (RateLimitedException)` 退避分支继续生效。
- 其他错误（5xx / IOException）降级到下一端点。
- 链尾仍失败时抛出 `lastError`。

### 2. 流式中断不降级（chat）

`chat` 流式 emit 部分 token 后中断时，引擎**直接抛出 `IOException`**，
不进入下一端点的降级链——避免下游消费者收到
「端点 A 部分内容 + 端点 B 完整内容」的跨模型内容拼接。

- 尚未 emit 时遭遇非持久性错误，降级到下一端点重试流式。
- 调用方收到 `IOException` 后可自行决定是否整体重试。
- 当前所有调用方均使用 `chatSync`，`chat` 路径无调用方，但接口契约与实现一致。

### 3. JSON mode prompt 降级

当 `ChatConfig.jsonMode=true` 但端点未声明 `ModelCapability.JSON_MODE_NATIVE` 时，
在 system prompt 中追加 JSON 约束指令做 prompt 降级：

```kotlin
private const val JSON_MODE_HINT =
    "请严格只输出合法 JSON 对象，不要包含 markdown 代码块标记或额外说明。"
```

- 端点声明 `JSON_MODE_NATIVE` 时由 client 内部走原生 `response_format`（OpenAI）/`responseMimeType`（Gemini）。
- Anthropic 不支持原生 JSON mode，由引擎层走 prompt 降级。
- 已有 system prompt 时追加约束，否则插入新的 system 消息。

### 4. SDK 异常识别（基于反射）

`isPersistentError` / `isRateLimited` 通过**反射调用 SDK 异常基类的 `statusCode()`** 方法
统一识别 HTTP 状态码，而非依赖类名字符串匹配：

```kotlin
private fun extractSdkStatusCode(e: Throwable): Int? = runCatching {
    val method = e::class.java.getMethod("statusCode")
    (method.invoke(e) as? Int) ?: (method.invoke(e) as? Number)?.toInt()
}.getOrNull()
```

- **已通过 javap 验证**：`com.openai.errors.OpenAIServiceException.statusCode() : int`
  与 `com.anthropic.errors.AnthropicServiceException.statusCode() : int`，
  子类（`BadRequestException` / `UnauthorizedException` / `RateLimitException` 等）继承并 override。
- 旧实现用 `className.contains("OpenAI"/"Anthropic")` 大小写敏感匹配，但 SDK 异常位于
  `com.openai.errors` / `com.anthropic.errors` 包，简单名不含厂商前缀、包名为小写，
  恒返回 false（详见 PR #264 review）。反射方式直接命中基类方法，无类名拼写风险。
- 非 SDK 异常（如 `IOException`）无此方法，返回 null 由调用方走 message 正则兜底
  `Regex("""\b(4\d{2}|5\d{2})\b""")`。

## ChatMessage 与多模态

```kotlin
data class ChatMessage(
    val role: Role,
    val content: List<ContentPart>,
) {
    enum class Role { SYSTEM, USER, ASSISTANT }
    constructor(role: Role, text: String) : this(role, listOf(ContentPart.Text(text)))
}

sealed interface ContentPart {
    data class Text(val text: String) : ContentPart
    data class Image(val url: String, val mimeType: String) : ContentPart
    data class Audio(val url: String, val mimeType: String) : ContentPart
    data class Video(val url: String, val mimeType: String) : ContentPart
}
```

- 旧版 `content: String` 扩展为 `List<ContentPart>`，支持文本 / 图像 / 音频 / 视频块。
- 纯文本调用方可继续用 `ChatMessage(role, text)` 便捷构造（90%+ 调用场景）。
- `textContent()` 取首个文本块；`hasMultimodalContent()` 判断是否含非文本块。
- 当前所有调用方均为纯文本，多模态降级链留待后续接入。

## ChatConfig

```kotlin
data class ChatConfig(
    val temperature: Float = 0.8f,
    val maxTokens: Int = 512,
    val jsonMode: Boolean = false,
)
```

- `jsonMode` 由 `DefaultRulesetEngine` 按端点能力声明选择原生方式或 prompt 降级（见上文）。

## OpenAI 兼容协议（openai/）

### OpenAiCompatibleClient

```kotlin
class OpenAiCompatibleClient(private val endpoint: EndpointConfig) : LlmClient
```

用 OpenAI Java SDK 4.43.0 取代旧手写 Retrofit + SSE 解析，统一覆盖：
- OpenAI 官方端点
- 兼容 OpenAI 协议的第三方端点（Deepseek / Moonshot / 智谱 / SiliconFlow / 本地 Ollama）
- Google Gemini 官方 OpenAI 兼容端点（`https://generativelanguage.googleapis.com/v1beta/openai/`）

**SDK builder 配置**：

```kotlin
OpenAIOkHttpClient.builder()
    .apply {
        endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { apiKey(it) }
        baseUrl(endpoint.baseUrl)
        maxRetries(2)   // 显式设置，与 AnthropicCompatibleClient 与旧 RetryInterceptor 等价
    }
    .build()   // 返回 OpenAIClient（接口类型）
```

**SDK 4.43.0 关键 API 形态**（已通过反编译 jar 验证）：
- 包名 `com.openai.models.chat.completions`（**复数 completions**）。
- `OpenAIOkHttpClient.builder().build()` 返回 `OpenAIClient`（接口类型）。
- `client.chat().completions().createStreaming(params)` 返回
  `StreamResponse<ChatCompletionChunk>`；`.stream()` 返回
  `java.util.stream.Stream<ChatCompletionChunk>`。
- 流式迭代需用 `iterator()` 显式循环（`forEach` 的 `Consumer` 非 suspend，不能 emit）。
- `ChatCompletionMessageParam` 是 union 类型，无 builder，用 `ofSystem/ofUser/ofAssistant`
  工厂包装具体角色 message param。
- 多模态用 `ChatCompletionContentPart` union + 顶层 `ChatCompletionContentPartText` /
  `ChatCompletionContentPartImage` 类构造。
- `ChatCompletion.Choice.finishReason()` 非 Optional；`ChatCompletionMessage.content()` 返回 `Optional<String>`。
- `ChatCompletionChunk.Choice.finishReason()` 是 Optional（与非流式不同）。

**JSON mode**：端点声明 `ModelCapability.JSON_MODE_NATIVE` 时通过 SDK
`response_format = ResponseFormatJsonObject` 原生请求；否则由 `DefaultRulesetEngine`
在 system prompt 追加约束指令做 prompt 降级（在引擎层处理，本类不感知 `jsonMode`）。

**流式失败降级**：
- 尚未 emit 任一 token 时遭遇非持久性错误（5xx / IO），自动降级为 `chatSync` 一次性 emit。
- 已 emit 后中断则抛 `IOException("streaming truncated after partial emit")` 通知调用方内容不完整。
- 持久性 HTTP 4xx（401 / 403 / 400 等）不降级，直接 rethrow。

### Role 映射

| Role | OpenAI |
| --- | --- |
| `SYSTEM` | `system` |
| `USER` | `user` |
| `ASSISTANT` | `assistant` |

## Anthropic 兼容协议（anthropic/）

### AnthropicCompatibleClient

```kotlin
class AnthropicCompatibleClient(private val endpoint: EndpointConfig) : LlmClient
```

用 Anthropic Java SDK 2.34.1 取代旧手写 Retrofit + SSE 解析。

**SDK builder 配置**：

```kotlin
AnthropicOkHttpClient.builder()
    .apply {
        endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { apiKey(it) }
        baseUrl(endpoint.baseUrl)
        maxRetries(2)   // SDK 内置 429/5xx 重试，与旧 RetryInterceptor 等价
    }
    .build()   // 返回 AnthropicClient（接口类型）
```

**SDK 2.34.1 关键 API 形态**（已通过反编译 jar 验证）：
- `AnthropicOkHttpClient.builder().build()` 返回 `AnthropicClient`（接口类型）。
- `client.messages().createStreaming(params)` 返回
  `StreamResponse<RawMessageStreamEvent>`；`.stream()` 返回
  `java.util.stream.Stream<RawMessageStreamEvent>`。
- `RawMessageStreamEvent` 是 union，本身没有 `.delta()` / `.error()` 方法，
  必须先 `isContentBlockDelta()` 判断，再 `asContentBlockDelta().delta()` 拿
  `RawContentBlockDelta`；它的方法名是 `isText()` / `asText()`（**不是** `isTextDelta()` / `asTextDelta()`）。
  错误通过异常抛出，不走事件。
- `MessageParam` 有 builder，但 `Role` 只有 USER / ASSISTANT，SYSTEM 消息走
  `MessageCreateParams.builder().system(String)`。
- `Message.content()` 返回 `List<ContentBlock>`（不是 String），
  要遍历取 `TextBlock.asText().text()`。

**System 处理**：所有 SYSTEM 消息 `joinToString("\n")` 合并为 `system` 字段，
过滤出非 SYSTEM 消息走 `messages(List<MessageParam>)`。

**JSON mode**：Anthropic 不支持原生 `response_format`，由 `DefaultRulesetEngine`
在 system prompt 追加约束指令实现（本类不感知 `jsonMode`）。

### Role 映射

| Role | Anthropic |
| --- | --- |
| `USER` | `user` |
| `ASSISTANT` | `assistant` |
| `SYSTEM` | （提取到 `MessageCreateParams.system`，不进 `messages`） |

## EndpointConfigProvider 抽象

```kotlin
interface EndpointConfigProvider {
    suspend fun listEndpoints(): List<LlmEndpointEntity>
    suspend fun getEndpoint(id: String): LlmEndpointEntity?
    suspend fun getEndpointApiKey(endpointId: String): String?
    fun observeEndpointChanges(): Flow<Unit>
}
```

- 全 `suspend` 避免 `runBlocking` ANR。
- 由 app 模块 `AppEndpointConfigProvider` 实现（基于 `ConfigRepository`）。
- `listEndpoints` 首次调用触发旧 provider 配置迁移（幂等）。
- `observeEndpointChanges` 任何端点 CRUD / API Key 变更后发出 Unit。

## EndpointConfig（运行时视图）

```kotlin
data class EndpointConfig(
    val id: String,
    val displayName: String,
    val protocol: LlmProtocol,
    val baseUrl: String,
    val model: String,
    val capabilities: Set<ModelCapability>,
    val apiKey: String?,       // 运行时注入，不持久化于 Room
    val orderIndex: Int,
)
```

- 由 `EndpointRegistry` 从 `LlmEndpointEntity` + API Key 组装。
- API Key 在创建 client 时一次性读取注入，避免每次请求都跑 EncryptedSharedPreferences。
- `supports(capability)` / `supportsStreaming` 便捷判断。

## Room v7→v8 迁移

- `AppDatabase` version=8，新增 `llm_endpoints` 表（`LlmEndpointEntity`）。
- `MIGRATION_7_8` 显式 `CREATE TABLE llm_endpoints` + 索引。
- `fallbackToDestructiveMigrationOnDowngrade`：降级时清空不丢历史数据。
- schema JSON 输出至 `core-data/schemas/com.trae.social.core.data.db.AppDatabase/8.json`。
- API Key 仍走 `EncryptedSharedPreferences`（命名空间 `api_key_ep_<endpointId>`），
  与 Room 元数据分离，符合安全要求。

### 旧 provider 配置迁移

`ConfigRepository.migrateLegacyProviderConfigsIfNeeded`：
- 幂等：用 `KEY_ENDPOINT_MIGRATION_DONE` 标记 + `migrationMutex` 双保险。
- 旧 4 个 provider 槽位（OPENAI / ANTHROPIC / GEMINI / CUSTOM）的 EncryptedSharedPreferences
  配置读取后创建端点。
- `default_provider` 决定 orderIndex=0 的主端点。
- 旧 API Key 复制到 `api_key_ep_<endpointId>` 新命名空间，旧 key 保留不删（回滚安全）。
- 迁移时按 provider 给端点赋予不同的 capabilities：
  - OPENAI / CUSTOM / GEMINI：`TEXT + JSON_MODE_NATIVE + VISION_INPUT + STREAMING`（GEMINI 无 STREAMING）
  - ANTHROPIC：`TEXT + STREAMING`（无 `JSON_MODE_NATIVE`，避免引擎走原生 `response_format` 失败）

## LlmProtocol 枚举

```kotlin
enum class LlmProtocol(val id: String, val displayName: String, val defaultBaseUrl: String) {
    OPENAI_COMPATIBLE("openai_compat", "OpenAI 兼容", "https://api.openai.com/v1/"),
    ANTHROPIC_COMPATIBLE("anthropic_compat", "Anthropic 兼容", "https://api.anthropic.com/");
}
```

- 把「提供商」与「API 协议格式」解耦：用户配置的端点只需声明走哪种协议格式，不必绑定到特定提供商。
- 不再保留 GEMINI_COMPATIBLE：Gemini 走 OPENAI_COMPATIBLE 的官方兼容端点
  `https://generativelanguage.googleapis.com/v1beta/openai/`，由 OpenAI SDK 统一承载。

## ModelCapability 枚举

```kotlin
enum class ModelCapability {
    TEXT,                  // 基础文本生成 / 对话（所有模型必声明）
    JSON_MODE_NATIVE,      // 原生结构化输出（OpenAI response_format / Gemini responseMimeType）
    VISION_INPUT,          // 图像理解
    IMAGE_GENERATION,      // 文生图 / 图生图
    AUDIO_INPUT,           // 语音理解
    AUDIO_OUTPUT,          // TTS
    VIDEO_INPUT,           // 视频输入理解
    STREAMING,             // 是否可靠支持流式输出
}
```

- `parseSet(stored: String?)`：从 Room 持久化的逗号分隔字符串解析为集合，未知 token 静默丢弃（前向兼容）。
- `Set<ModelCapability>.toStorageString()`：序列化为逗号分隔字符串。
- 端点声明 `JSON_MODE_NATIVE` 才用原生方式请求 JSON，否则走 prompt 降级。

## RateLimitedException（保留）

```kotlin
class RateLimitedException(
    message: String,
    val retryAfterSeconds: Long? = null,
) : IOException(message)
```

- 继承 `IOException` 而非 `RuntimeException`（关键设计：避免 OkHttp `AsyncCall` 对非 `IOException` 重抛触发 "OkHttp Dispatcher" 线程未捕获异常闪退）。
- 各 Worker 按类型精确捕获后 `Result.success` 跳过，不重试不耗配额。
- `DefaultRulesetEngine` 把 SDK 429 异常转换为此类型向上传递。

## 旧 Interceptor / Retrofit 抽象已移除

以下旧实现已完全删除（详见 #151）：
- `OpenAiClient` / `AnthropicClient` / `GeminiClient`（手写 Retrofit + SSE）
- `AuthInterceptor` / `RetryInterceptor` / `LoggingInterceptor`（OkHttp 拦截器链）
- `LlmProviderRegistry`（按 `LlmProvider` 寻址）
- `LlmConfigProvider` / `AppLlmConfigProvider`（旧配置抽象）
- `LlmHttp` 常量对象
- `OpenAiApi` / `AnthropicApi` / `GeminiApi`（Retrofit interface）
- `OpenAiRequest` / `OpenAiResponse` / `AnthropicRequest` / `AnthropicStreamEvent` / `GeminiRequest` 等 DTO
- `StreamingJsonArrayParser`（Gemini chunked JSON 解析器）

SDK 内置鉴权头注入、429/5xx 指数退避重试、连接池管理，无需手写拦截器链。
旧 `RateLimitInterceptor` 类已移除，限流统一由 core-scheduler 的 `SchedulerRateLimiter`
在调度入口执行，避免双层限流。

## Hilt DI（di/LlmModule.kt）

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {
    @Binds @Singleton
    abstract fun bindRulesetEngine(impl: DefaultRulesetEngine): RulesetEngine
}
```

- 仅做接口到实现的绑定，不需要全局共享 OkHttpClient / Retrofit / Json。
- 各 SDK client 内部自行构造 OkHttpClient 并直接调用官方 Java SDK，鉴权头与重试均由 SDK 内置。
- `EndpointRegistry` 为 `@Singleton class @Inject constructor`，Hilt 自动构造。
- `EndpointConfigProvider` 的具体实现绑定声明位于 app 模块的 `AssetProviderModule`
  （实现类 `AppEndpointConfigProvider` 依赖 app 模块的 `ConfigRepository`）。

## RateLimiter（令牌桶，保留）

```kotlin
RateLimiter(maxTokens = 30, refillIntervalMillis = 60_000, nowProvider)
```

- `acquire()`：计算到下次补充精确等待时间 `refillIntervalMillis - (elapsed % refillIntervalMillis)`，
  `delay(waitMs.coerceAtLeast(1))`，避免 50ms 轮询忙等（IMPL-31）。
- `tryAcquire()` 非阻塞。
- `availableTokens()` 当前可用。
- `reconfigure(newMaxTokens)`：按比例折算保留令牌。
- `refillLocked()`：`elapsed` 为负（时钟回拨）时重置基准。
- 当前由 core-scheduler 的 `SchedulerRateLimiter` 在调度入口使用。

## Prompt 工程（prompt/）

本节为概览，完整 Prompt 模板请参见 `12-LLM-集成与-Prompt-工程.md`。

文件清单：

- `TweetPromptBuilder`（+ `TweetPostProcessor`）：推文生成 prompt 构建与后处理。
- `CommentPromptBuilder`：评论生成 prompt 构建。
- `PersonaUpdatePromptBuilder`：人格动态更新 prompt 构建。
- `FeedbackAgentPromptBuilder`：用户反馈智能体 prompt。
- `UserProfilePromptBuilder`：用户画像生成 prompt。
- `PromptUtils`：prompt 工具函数。

## 测试覆盖

`test/` 下：

- `CommentPromptBuilderTest`
- `ContentFilterTest`
- `PersonaUpdatePromptBuilderTest`
- `PromptUtilsTest`
- `TweetPostProcessorTest`
- `TweetPromptBuilderTest`
- `EventTextPreParserTest`（#151 后 mock `RulesetEngine`，调用点从 `llmRegistry.getClient(provider)` 迁到 `rulesetEngine.chatSync(...)`）

覆盖 prompt 解析容错、错别字替换、emoji 追加、截断、相似度校验、敏感词命中。

## 已知 follow-up（非本 PR 阻塞）

- `DefaultRulesetEngine` / `EndpointRegistry` / 两个 `CompatibleClient` 的单测仍待补
  （PR #264 review Major 6 已记录，作为 follow-up）。
- `ContentFilter` 敏感词子串匹配过宽问题（IMPL-29）原计划改进，本 PR 直接删除了 `ContentFilter`，
  本地敏感词防线暂缺，待后续 PR 用 hook 点（如 `ISensitiveContentFilter` 接口空实现 + TODO）补回。
- `ApiKeyManagementScreen` 新增端点固定使用 `LlmProtocol.OPENAI_COMPATIBLE`，
  Anthropic 端点需通过迁移或代码注入创建，UI protocol 选择器待后续。
- `migrateLegacyProviderConfigsLocked` 通过 `displayName` 匹配默认 provider 的边缘 case
  （用户改了 displayName 后再触发迁移），建议后续用 `protocol.id` 或新增 `legacyProvider` 字段。
- `UserProfileWorker` 的 `no_default_provider` 状态字符串未更新为 `no_endpoint_configured`。
