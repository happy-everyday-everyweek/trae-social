## 重新打开：PendingInteractionWorker processed 计数仍虚高

经审查，`core-scheduler/src/main/java/com/trae/social/core/scheduler/work/PendingInteractionWorker.kt` L91-L104 仍使用 `processed += executable.size`（按批大小累加，未按实际执行数）。

- DB 层（`core-data/src/main/java/com/trae/social/core/data/dao/InteractionDao.kt` 的 `executeInteractionsAndUpdateTweet`）已通过 `markExecuted` 返回 `rowsAffected==0` 跳过计数，原子事务正确
- 新增 `finalStatus='no_pending'` 仅在 `processed==0 && failed==0` 时区分完全空操作场景
- 但 Worker 层并未感知 `rowsAffected==0` 的跳过数，并发重复扫描导致部分 rowsAffected==0 被跳过时，`processed` 仍按 `executable.size` 累加，计数虚高问题未在 Worker 层解决

issue 建议返回实际执行数并 `processed += actualExecuted` 未采纳。请按建议方向落地后再次关闭。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
