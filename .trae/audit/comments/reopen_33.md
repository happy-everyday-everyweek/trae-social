## 重新打开：动态字号与列表项整条语义未落地

经审查，`feature-feed/src/main/java/com/trae/social/feed/TweetCard.kt` 已为 IconButton 与 AsyncImage 添加 `contentDescription`，并通过 `minTouchTarget()` 扩展点击热区（issue 三项要求中的两项已完成）。但未完成的部分：

- **动态字号**：未引入 `LocalConfiguration.fontScale` 边界处理，`SocialTypography` 仍使用固定 sp，未显式适配超大字号裁剪（如 `maxLines + ellipsis` 或 `PlatformTextStyle`）。
- **列表项整条语义**：未对推文整条 `Row`/`Card` 包裹 `Modifier.semantics(mergeDescendants = true)`，无障碍模式逐项朗读零碎。

请补全上述两项后再次关闭。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
