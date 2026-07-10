# core-llm LLM 层

LLM 抽象层，namespace `com.trae.social.llm`。通过 `api(project(":core-data"))` 复用 `LlmProvider` 枚举。封装 OpenAI / Anthropic / Gemini / 自定义（OpenAI 兼容）四类提供商。

## LlmClient 抽象

```kotlin
interface LlmClient {
    suspend fun chat(messages: List<ChatMessage>, config: ChatConfig): Flow<String>
    suspend fun chatSync(messages: List<ChatMessage>, config: ChatConfig): String
    suspend fun ping(): Boolean
    val provider: LlmProvider
}
```

- `chat` 流式逐 token 返回；流式失败且未 emit 任何 token 时自动降级 `chatSync`。
- `ChatMessage(role: Role, content)`，`Role` 枚举 `SYSTEM` / `USER` / `ASSISTANT`。
- `ChatConfig(temperature=0.8f, maxTokens=512, jsonMode=false)`。
  - `jsonMode` 仅 OpenAI 原生支持，Anthropic 走 prompt 约束，Gemini 走 `responseMimeType`。

## LlmConfigProvider 抽象

```kotlin
interface LlmConfigProvider {
    suspend fun getApiKey(provider: LlmProvider): String
    suspend fun getBaseUrl(provider: LlmProvider): String
    suspend fun getModel(provider: LlmProvider): String
    suspend fun getDefaultProvider(): LlmProvider
}
```

- 全 `suspend` 避免 `runBlocking` ANR。
- 由 app 模块 `AppLlmConfigProvider` 实现。
- `DefaultModels` 单例：

| Provider | 默认模型 |
| --- | --- |
| `OPENAI` | `"gpt-4o-mini"` |
| `ANTHROPIC` | `"claude-3-5-sonnet-20240620"` |
| `GEMINI` | `"gemini-1.5-flash"` |
| `CUSTOM` | `"gpt-4o-mini"` |

## LlmHttp 常量

| 常量 | 值 |
| --- | --- |
| `CONNECT_TIMEOUT` | 15s |
| `READ_TIMEOUT` | 60s |
| `WRITE_TIMEOUT` | 30s |
| `DEFAULT_RPM` | 30 |
| `MAX_RETRY_ATTEMPTS` | 3 |
| `RETRY_BASE_DELAY_MS` | 500 |
| `OPENAI_BASE_URL` | - |
| `ANTHROPIC_BASE_URL` | - |
| `GEMINI_BASE_URL` | - |
| `ANTHROPIC_VERSION` | `"2023-06-01"` |
| `PROVIDER_HEADER` | `"X-Llm-Provider"` |

- `maskApiKey(key)` 顶层函数：脱敏为 `前4...后4`；`<=8` 返回 `***`。

## LlmProviderRegistry（客户端缓存）

- `@Singleton`，注入三个 `@Named` Retrofit + `OkHttpClient` + `configProvider` + `json`。
- `clients: ConcurrentHashMap<LlmProvider, LlmClient>`，`mutex: Mutex`。
- `getClient(provider)`：双重检查锁定，未命中则 `mutex.withLock` 内 `createClient`。
- `getDefaultClient()` 委托 `getClient(getDefaultProvider())`。
- `invalidateCache()`：`mutex.withLock { clients.clear() }`（P2 修复获取锁后再 clear 避免 TOCTOU）。切换默认提供商/Key/URL/模型变更时调用。
- `createClient` 工厂：
  - `CUSTOM` 复用 `OpenAiClient` 但构造时传 `provider=CUSTOM`。
  - 自定义 Base URL 时 `buildRetrofit(baseUrl)` 基于共享 `OkHttpClient` 新建 Retrofit，`ensureTrailingSlash` 保证 URL 以 `/` 结尾。

## OpenAI 协议（openai/）

### OpenAiApi

- `POST v1/chat/completions`。
- `@Streaming streamChat` + `chat`，均 `@Header(LlmHttp.PROVIDER_HEADER) provider` 动态注入（P1 修复：不再 `@Headers` 硬编码 `OPENAI`，使 `CUSTOM` 携带正确标识）。

### 请求结构

```kotlin
OpenAiRequest(
    model,
    messages,
    temperature = 0.8f,
    max_tokens = 512,
    stream = false,
    response_format: OpenAiResponseFormat?
)
```

- `response_format` `type="json_object"` 为 JSON mode。

### 响应

- `OpenAiResponse(choices)`，`OpenAiChoice(message / delta / finish_reason)`。

### OpenAiClient

- `OpenAiClient(api, model, provider, json)`：`provider` 构造注入（CUSTOM 也能正确报告）。

### 流式 SSE 解析

- 逐行 `readUtf8Line`，只处理 `"data:"` 前缀行，`"[DONE]"` 时 break，否则 decode `OpenAiResponse` 取 `choices[0].delta.content` emit。
- 常量 `DATA_PREFIX="data:"`, `DONE_MARKER="[DONE]"`。

### 降级（RISK-4）

- 流式异常时若已 emit 部分 token 抛 `IOException("streaming truncated after partial emit")`（IMPL-8 避免残缺推文）。
- 未 emit 则 `runCatching { chatSync }.getOrDefault("")` 单条 emit。

### Role 映射

| Role | OpenAI |
| --- | --- |
| `SYSTEM` | `system` |
| `USER` | `user` |
| `ASSISTANT` | `assistant` |

## Anthropic 协议（anthropic/）

### AnthropicApi

- `POST v1/messages`。
- `@Headers` 硬编码 `"X-Llm-Provider: ANTHROPIC"`（无 CUSTOM 场景）。

### AnthropicRequest

```kotlin
AnthropicRequest(
    model,
    max_tokens,
    system: String?,
    messages,
    temperature,
    stream
)
```

- `system` 是顶层字段。

### 流式事件

- `event:<type>` + `data:<json>`。
- 仅处理 `data` 行解析 `AnthropicStreamEvent`：
  - `type=="error"` 抛 `IOException`（IMPL-28 处理 quota/内容拦截）
  - `"message_stop"` break
  - `"content_block_delta"` 取 `delta.text` emit
- `chatSync` 取 `content.firstOrNull{type=="text"}?.text`。

### System 处理

- 所有 SYSTEM 消息 `joinToString("\n")` 合并为 `system` 字段，过滤出非 SYSTEM 消息。

### JSON mode（RISK-13）

- 不支持原生，`jsonMode=true` 时在 system prompt 末尾追加：

```
JSON_MODE_HINT = "请严格只输出合法 JSON 对象，不要包含 markdown 代码块标记或额外说明。"
```

### Role 映射

| Role | Anthropic |
| --- | --- |
| `USER` | `user` |
| `ASSISTANT` | `assistant` |
| `SYSTEM` | `user`（system 提取到顶层后剩余 system 也映射为 user） |

## Gemini 协议（gemini/）

### GeminiApi

- 流式 `POST v1beta/models/{model}:streamGenerateContent`，非流式 `:generateContent`。
- `@Headers` 硬编码 `"X-Llm-Provider: GEMINI"`。
- 鉴权 `?key=API_KEY` 由 `AuthInterceptor` 注入。

### GeminiRequest

```kotlin
GeminiRequest(
    contents,
    systemInstruction: GeminiContent?,
    generationConfig: GeminiGenerationConfig?
)
```

- `generationConfig` 含 `temperature` / `maxOutputTokens` / `responseMimeType`。

### 流式解析（非 SSE）

- 响应为 chunked JSON 数组，自定义 `StreamingJsonArrayParser` 私有内部类。
- 花括号深度计数逐元素解析（`depth++` / `--`，字符串内 `{}` 不计入，处理 `\` 转义），`depth==0` 时回调 `onObject`。
- `feed(chars)` 接收字符块，`finish()` 检查 `depth!=0` 抛 `IOException`。
- `CHUNK_SIZE=8192`。
- 增量文本位于 `candidates[0].content.parts[0].text`。

### JSON mode

- Gemini 1.5+ 原生支持，`responseMimeType="application/json"`。

### System 处理

- SYSTEM 消息合并为 `GeminiContent` 放入 `systemInstruction`。

### Role 映射

| Role | Gemini |
| --- | --- |
| `USER` | `user` |
| `ASSISTANT` | `model` |
| `SYSTEM` | `user` |

## Hilt DI（di/LlmModule.kt）

### provideJson(@Singleton)

```kotlin
Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}
```

### provideOkHttpClient(@Singleton)

- 拦截器链顺序 Auth -> Retry -> Logging（IMPL-32）。
- `connectTimeout 15s` / `read 60s` / `write 30s`。

### 三个 @Named Retrofit

- `openai` / `anthropic` / `gemini` 共享 `OkHttpClient` 与 `Json`。

### 重要（IMPL-26）

- `RateLimitInterceptor` 未加入 HTTP 链，限流统一由 core-scheduler 的 `SchedulerRateLimiter` 在调度入口执行，避免双层限流。
- `RateLimitInterceptor` 类保留备用。

### LlmConfigProvider

- 需由 app 模块提供实现，否则编译期 Hilt 报缺失绑定。

## AuthInterceptor

- 读取 `X-Llm-Provider` 头 `parseProvider` 为 `LlmProvider`（空/失败默认 `CUSTOM`，`valueOf(header.uppercase())`）。
- `runBlocking { configProvider.getApiKey(provider) }`。移除内部头。
- Key 为空抛 `IOException("API key not configured for $provider")`（IMPL-27）。
- 按 provider 注入：
  - `OPENAI` / `CUSTOM` -> `Authorization: Bearer <key>`
  - `ANTHROPIC` -> `x-api-key: <key>` + `anthropic-version: 2023-06-01`
  - `GEMINI` -> URL 追加 `?key=<key>`
- 异常兜底：`IOException` 原样传递（含 `RateLimitedException`）；非 `IOException` 转 `IOException` 避免 OkHttp `AsyncCall` 重抛导致闪退。

## RetryInterceptor

- `maxAttempts=3`, `baseDelayMs=500`。
- `429`：直接抛 `RateLimitedException` 不重试（读 `Retry-After` 秒传入）。IMPL-19。
- `5xx`：关闭响应后重试，优先读 `Retry-After`（上限 `MAX_RETRY_AFTER_SECONDS=60`），否则指数退避。重试耗尽抛 `IOException("server error $code after $attempt attempts")`（IMPL-7 不返回已关闭 Response）。
- `IOException`：重试，退避公式 `baseDelayMs * 2^(attempt-1)`。
- 实际退避序列：`500ms` / `1000ms` / `2000ms`（注意：与某些文档描述的 `10s/30s/90s` 不符，以代码为准）。
- `Timber.d` 记录每次重试（IMPL-32）。

## RateLimitInterceptor 与 RateLimitedException

### RateLimitInterceptor(rateLimiter)

- `intercept` 内 `runBlocking { rateLimiter.acquire() }`。
- 当前未装配到 HTTP 链。

### RateLimitedException(message, retryAfterSeconds)

- 继承 `IOException` 而非 `RuntimeException`（关键设计：避免 OkHttp `AsyncCall` 对非 `IOException` 重抛触发 "OkHttp Dispatcher" 线程未捕获异常闪退）。
- 各 Worker 按类型精确捕获后 `Result.success` 跳过，不重试不耗配额。

## LoggingInterceptor

- 仅 `Timber.treeCount!=0` 时记录（release 不 plant 树自动跳过）。
- 不打印请求体。不读取 `Authorization` / `x-api-key` 头。
- `sanitizeUrl`：URL 中 query 参数名（忽略大小写）为 `key` 时值替换为 `maskApiKey`。其余原样。
- 日志格式：
  - 成功 `"<-- %d %s %s (%dms)"`
  - 失败 `"--> %s %s FAIL (%dms): %s"`
- 耗时 `System.nanoTime`。

## RateLimiter（令牌桶）

```kotlin
RateLimiter(maxTokens = 30, refillIntervalMillis = 60_000, nowProvider)
```

- `acquire()`：计算到下次补充精确等待时间 `refillIntervalMillis - (elapsed % refillIntervalMillis)`，`delay(waitMs.coerceAtLeast(1))`，避免 50ms 轮询忙等（IMPL-31）。
- `tryAcquire()` 非阻塞。
- `availableTokens()` 当前可用。
- `reconfigure(newMaxTokens)`：按比例折算保留令牌 `(availableTokens * newMaxTokens / maxTokens)`，不替换实例（IMPL-26/30）。
- `refillLocked()`：`elapsed` 为负（时钟回拨）时重置基准（IMPL-31）。

## Prompt 工程（prompt/）

本节为概览，完整 Prompt 模板请参见 `12-LLM-集成与-Prompt-工程.md`。

文件清单：

- `TweetPromptBuilder`（+ `TweetPostProcessor`）：推文生成 prompt 构建与后处理。
- `CommentPromptBuilder`：评论生成 prompt 构建。
- `PersonaUpdatePromptBuilder`：人格动态更新 prompt 构建。
- `ContentFilter`：内容安全过滤。
- `PromptUtils`：prompt 工具函数。

## 测试覆盖

`test/` 下：

- `CommentPromptBuilderTest`
- `ContentFilterTest`
- `PersonaUpdatePromptBuilderTest`
- `PromptUtilsTest`
- `TweetPostProcessorTest`
- `TweetPromptBuilderTest`

覆盖 prompt 解析容错、错别字替换、emoji 追加、截断、相似度校验、敏感词命中。
