## 重新打开：determineAccountLastRunTime 仍未按 status 过滤

经审查，`core-scheduler/src/main/java/com/trae/social/core/scheduler/work/TweetGenerationWorker.kt` L187-L188 已将 resultStatus 从 `skipped_empty_response` 改为 `retry_empty_response`（建议方向 1 完成），但：

- `core-scheduler/src/main/java/com/trae/social/core/scheduler/SchedulerInitializer.kt` L269-L283 的 `determineAccountLastRunTime` 仍使用 `recent.firstOrNull { it.action == "tweet_generation" }`，未按 `status == "success"` 过滤——`retry_empty_response` / `error_*` 等失败重试日志会被当作"上次运行"时刻，导致补发范围错乱（建议方向 2 未应用）
- 未新增 `SchedulerLogDao.getRecentByActionAndStatus` 等带 status 过滤的 DAO 方法（建议方向 3 未应用）

请补全 status 过滤后再次关闭。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
