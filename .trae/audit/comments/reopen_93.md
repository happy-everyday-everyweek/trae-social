## 重新打开：PendingInteractionWorker 仍用阻塞 acquire()

经审查，`core-llm/src/main/java/com/trae/social/llm/ratelimit/RateLimiter.kt` L61 已新增 `acquireWithTimeout(timeoutMillis: Long): Boolean`，且 `TweetGenerationWorker.kt:122`、`InteractionWorker.kt:414`、`PersonaUpdateWorker.kt:91` 均已切换为 `acquireWithTimeout(WorkerConstants.ACQUIRE_TIMEOUT_MS)`，超时返回 `Result.retry()`。但：

- `core-scheduler/src/main/java/com/trae/social/core/scheduler/work/PendingInteractionWorker.kt` L163 的 `generateCommentsFor` 仍使用 `rateLimiter.acquire()` 无超时阻塞调用
- `processBatch` 内对每个 interaction 循环 acquire，多个挂起可能累积超过 WorkManager 10 分钟执行超时

请将 `PendingInteractionWorker` 中的 acquire 也切换为 `acquireWithTimeout` 后再次关闭。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
