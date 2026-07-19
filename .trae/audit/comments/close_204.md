## 关闭：已修复

经审查，`core-designsystem/src/main/java/com/trae/social/designsystem/components/CapsuleTab.kt` L53-L64 的 colorSpec 已改为 NoBouncy + StiffnessMediumLow，并在 reduceMotion 下走非弹跳分支。满足 issue 全部 acceptance criteria（颜色过渡不应 overshoot）。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
