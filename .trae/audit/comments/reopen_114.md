## 重新打开：determineAccountLastRunTime 三返回路径仍不一致

经审查，`core-scheduler/src/main/java/com/trae/social/core/scheduler/SchedulerInitializer.kt` L269-L283 的 `determineAccountLastRunTime` 仍存在三返回路径不一致问题：

- 路径 A（无日志）：返回 `now.minusSeconds(24 * 60 * 60)` = 24 小时前
- 路径 B（异常）：通过 `.getOrNull()` 返回 `null`
- 路径 C（有日志）：返回 `Instant.ofEpochMilli(lastTweetLog.timestamp)`

`core-scheduler/src/main/java/com/trae/social/core/scheduler/rule/ScheduleRuleResolver.kt` L152-L156 中 `missedWindows` 仍把 `null` 处理为 `zonedNow.minusDays(1).toLocalDate().atStartOfDay(zone)` 即昨日 00:00，与路径 A 的 24 小时前可能相差数小时，三种返回路径产生不一致补发范围的问题依旧。

代码注释引用了 `#114` 但仅完成了 #72 的按账号拆分，未处理路径 A/B 不一致问题。请统一异常与无日志路径返回值后再次关闭。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
