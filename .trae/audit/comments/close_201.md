## 关闭：已修复（与 #155 重复）

经审查，`feature-feed/src/main/java/com/trae/social/feed/TweetCard.kt` L596-L597 的点赞心跳动画起点已改为 `scale.snapTo(0.8f)`（不再是 0.6f），并使用 LowBouncy + StiffnessMediumLow，且在 reduceMotion 模式下走 FastOutSlowInEasing 无弹跳分支。与 #155 是同一问题的英文重复 issue，已同步修复。满足全部 acceptance criteria。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
