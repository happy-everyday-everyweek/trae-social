## 重新打开：PersonaUpdateWorker 周期未缩短、Doze 下时延仍不可控

经审查，`core-data/src/main/java/com/trae/social/core/data/config/LlmConfig.kt` L36-L38 中 `personaUpdatePeriodDays` 仍为 `LOW=14 / MEDIUM=7 / HIGH=3` 天，未缩短。`core-scheduler/src/main/java/com/trae/social/core/scheduler/work/WorkerKeys.kt` L151-L161 的 `personaUpdatePeriodicRequest` 也未调用 `setInitialDelay`（锚定首执行时刻）、未调用 `setExpedited`（提升优先级）。反而把上限从 7 天放宽到 30 天，与 issue 建议方向相反。

issue 建议的 4 个方向均未应用：
1. 缩短周期至 6h/12h（未做）
2. `setInitialDelay` 锚定（未做）
3. `setExpedited` 提升优先级（未做）
4. 前台服务保障（未做）

Doze 模式下时延不可控问题依旧。请择一方向落地后再次关闭。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
