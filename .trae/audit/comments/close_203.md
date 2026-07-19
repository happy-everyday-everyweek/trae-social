## 关闭：已修复

经审查，`feature-publish/src/main/java/com/trae/social/publish/PublishScreen.kt` L363-L378 的 successScale 起点已改为 `0.85f`（不再是 0.3f），reduceMotion 下走 FastOutSlowInEasing 无过冲分支。满足 issue 全部 acceptance criteria（success icon 不应从过低的 scale 起步导致"凭空出现"感）。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
