# LLM 集成与 Prompt 工程

本文深入讲解多 LLM 提供商集成、HTTP 拦截器链、Prompt 模板、内容过滤与人设漂移防护。

## 四提供商协议对比

| 提供商 | 端点 | 鉴权方式 | JSON mode | 流式格式 | 默认模型 |
|--------|------|----------|-----------|----------|----------|
| OpenAI | POST `v1/chat/completions` | `Authorization: Bearer <key>` | `response_format` `type="json_object"` 原生 | SSE `data:` 行，`[DONE]` 终止 | `gpt-4o-mini` |
| Anthropic | POST `v1/messages` | `x-api-key: <key>` + `anthropic-version: 2023-06-01` | 不支持原生，prompt 追加 `JSON_MODE_HINT` | SSE `event:` + `data:` | `claude-3-5-sonnet-20240620` |
| Gemini | POST `v1beta/models/{model}:streamGenerateContent` / `:generateContent` | `?key=<key>` query 参数 | `responseMimeType="application/json"` 原生 | chunked JSON 数组（自定义 `StreamingJsonArrayParser`） | `gemini-1.5-flash` |
| 自定义（CUSTOM） | 复用 OpenAI 协议 | `Authorization: Bearer <key>` | 同 OpenAI | 同 OpenAI | `gpt-4o-mini` |

Note：CUSTOM 复用 `OpenAiClient` 但构造时传 `provider=CUSTOM`；自定义 Base URL 时 `buildRetrofit(baseUrl)` 新建 Retrofit。

## provider 头注入机制

- `OpenAiApi` 的 `streamChat`/`chat` 用 `@Header(LlmHttp.PROVIDER_HEADER) provider` 动态注入（P1 修复：不再 `@Headers` 硬编码 `OPENAI`），使 CUSTOM 端点携带正确标识。
- Anthropic/Gemini 用 `@Headers` 硬编码（无 CUSTOM 场景）。
- `AuthInterceptor` 读取 `X-Llm-Provider` 头 `parseProvider`（空/失败默认 `CUSTOM`），据此读取对应 API Key，移除内部头后注入鉴权。

## HTTP 拦截器链

- 顺序：Auth -> Retry -> Logging（IMPL-32，Logging 在 Retry 内部，每次重试都记录）。
- Auth：按 provider 注入鉴权，Key 为空抛 `IOException`。
- Retry：429 抛 `RateLimitedException` 不重试；5xx 关闭响应后重试（优先 `Retry-After` 上限 60s，否则指数退避 500ms/1000ms/2000ms）；`IOException` 重试。重试耗尽抛 `IOException`。
- Logging：仅 `Timber.treeCount!=0` 记录，不打印请求体，不读 `Authorization`/`x-api-key` 头，URL 中 `key` 参数 `maskApiKey` 脱敏。
- 重要：`RateLimitInterceptor` 未装配到 HTTP 链（IMPL-26），限流统一由 `SchedulerRateLimiter` 在调度入口执行。

## 流式降级策略（RISK-4）

- 流式异常时若已 emit 部分 token：抛 `IOException("streaming truncated after partial emit")`（IMPL-8，避免写入残缺推文）。
- 未 emit 任何 token：`runCatching { chatSync }.getOrDefault("")` 单条 emit。

## Prompt 模板：推文生成（TweetPromptBuilder）

- `PersonaInput`：`displayName`/`profession`/`ageRange`/`culturalBackground`/`worldview`/`values`/`languageStyle`/`catchphrase`/`emojiPreference(List)`/`typoRate(0.0-1.0)`/`recentMood`。
- `TweetGenerationResult`：`text(<=280)`/`withImage`/`imageTheme`（`ImageTheme` 枚举: `LANDSCAPE`/`FOOD`/`CITY`/`PET`/`SPORT`/`ART`/`TECH`/`NATURE`/`NONE`）/`interactionTendency(0.0-1.0)`。
- System Prompt：强制注入全部人设固定字段（【人设固定字段】列表），第一人称指令"你是该人物，以第一人称发布一条原创推文"，合规自检指令（RISK-12：不含暴力/仇恨/色情/对真实人物虚假陈述）。
- User Prompt：【当前时段】（如"工作日上午 09:00-12:00"）+【最近情绪】+【最近 3 条该账号推文（避免重复）】（去重键，空时"（暂无历史推文）"）+ JSON schema。
- `parseTweetResult`：`PromptUtils.extractJson` 提取 JSON（兼容 markdown 代码块），宽松解析（`ignoreUnknownKeys`/`isLenient`/`coerceInputValues`），`text` 缺失返回 `null`，`imageTheme` 非法 -> `NONE`，`interactionTendency` 缺失 0.5 超界 `coerceIn`。

## 推文后处理（TweetPostProcessor）

- 错别字替换表 `typoTable`（双向可互换）：的<->得/地，在<->再，做<->作。`applyTypos(text, typoRate, random)`：`typoRate>=1.0` 时所有可替换字符必定被替换（`nextDouble()` 永远 <1.0）。
- `appendEmojis(text, emojis, random)`：`emojis` 为空原样返回；`count = if(nextBoolean) 1 else 2`，从 `emojis` 随机选取拼接。emoji 字符由调用方传入（数据内容），源码不硬编码任何 emoji。
- `truncate(text, max=280)`：超长取前 `max-1` + `"…"`，`max<=1` 仅返回 `"…"`。
- 随机种子 `Random(windowStart + accountId.hashCode())` 保证可复现。

```kotlin
// 可复现随机种子示例
val random = Random(windowStart + accountId.hashCode())
val processed = tweetPostProcessor
    .applyTypos(text, typoRate, random)
    .let { tweetPostProcessor.appendEmojis(it, emojis, random) }
    .let { tweetPostProcessor.truncate(it, max = 280) }
```

## Prompt 模板：评论生成（CommentPromptBuilder）

- 一次调用为多个评论者批量生成（降低 LLM 调用成本）。
- `TweetInput(text, authorName, authorProfession)`，`CommentResult(commenterIndex, text, type: CommentType{COMMENT, LIKE, RETWEET})`。
- System Prompt："你将模拟多个不同人设的评论者..." + 人设一致性 + 合规自检。
- User Prompt：【被评推文】+【原作者人设简介】+【评论者人设列表】（逐行 `#index displayName（职业/年龄/风格/价值观/口癖/情绪）`）+ JSON 数组 schema。
- 约束：每条 `text<=100` 字符；`COMMENT` 必带 `text`；`LIKE`/`RETWEET` 可空；`commenterIndex` 对应下标。
- `parseCommentResults`：`extractJsonArray` 提取，逐条解析，单条字段缺失跳过该条不影响其余，`commenterIndex` 缺失/<0 跳过，`type` 非法 -> `COMMENT`。

## Prompt 模板：人设更新（PersonaUpdatePromptBuilder）

- `PersonaDynamicInput(lifeStory, workInfo, mood, relationshipNetwork)`，`PersonaUpdateResult(lifeStory, workInfo, mood)`。
- System Prompt（RISK-2）："你是人设演进引擎...保持人设一致性，不要突变：新内容应是在原内容基础上的自然推进，而非推翻重写。"
- User Prompt：【当前动态字段】+【最近一周活动事件】（编号列表，空时"（暂无近期事件）"）+ JSON schema。
- `parsePersonaUpdate`：任一关键字段缺失返回 `null`。
- `jaccardSimilarity(a, b)`（P2 修复：原名 `cosineSimilarity` 名不副实）：字符级 `|A ∩ B| / |A ∪ B|`，不依赖 NLP 库。
- `shouldRollback(old, new, threshold=0.5)`：相似度 < 0.5 时返回 `true`（人设突变，拒绝更新）。

```kotlin
// 字符级 Jaccard 相似度
fun jaccardSimilarity(a: String, b: String): Double {
    val sa = a.toSet()
    val sb = b.toSet()
    if (sa.isEmpty() && sb.isEmpty()) return 1.0
    if (sa.isEmpty() || sb.isEmpty()) return 0.0
    val inter = sa.intersect(sb).size
    val union = sa.union(sb).size
    return inter.toDouble() / union.toDouble()
}

fun shouldRollback(old: String, new: String, threshold: Double = 0.5): Boolean =
    jaccardSimilarity(old, new) < threshold
```

## 内容过滤（ContentFilter）

- 应用层合规兜底（RISK-12），prompt 已要求模型自检，本类再校验。
- 内置敏感词集合 `sensitiveWords`（大小写不敏感按字符匹配），涵盖：暴力类（杀人/杀戮/屠杀/炸弹/枪击/恐怖袭击等）、仇恨言论类（种族歧视/纳粹/法西斯等）、色情类（色情/淫秽/强奸/恋童癖等）、涉政敏感类（颠覆国家/分裂国家/藏独/疆独/台独/港独等）、毒品类（毒品交易）。
- `containsSensitiveContent(rawText)`：命中返回 `true`。
- `TweetGenerationWorker` 第 9 步命中 -> `"skipped_sensitive"` `Result.success`。

## Ping 连通性测试

- `LlmClient.ping()`：发送单条 USER `"ping"`，`ChatConfig(temperature=0.0f, maxTokens=8)`，响应非空即 `true`。
- 用于 `OnboardingViewModel.testConnection`，失败时 `classifyErrorByProbing` 调 `chatSync` 捕获具体异常（DNS/超时/401/403/404/429）。

## 测试覆盖

- `test/` 下 6 个测试类：`CommentPromptBuilderTest`/`ContentFilterTest`/`PersonaUpdatePromptBuilderTest`/`PromptUtilsTest`/`TweetPostProcessorTest`/`TweetPromptBuilderTest`。
- 覆盖 prompt 解析容错、错别字替换、emoji 追加、截断、相似度校验、敏感词命中。

## 相关代码位置

- 客户端：`core-llm/.../openai|anthropic|gemini/`
- 拦截器：`core-llm/.../interceptor/`
- Prompt：`core-llm/.../prompt/`
- 详见 [06-core-llm LLM 层](./06-core-llm-LLM-层.md)。
