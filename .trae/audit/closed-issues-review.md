# Closed Issues 审查总结

> 本文档由 issue 审查自动化任务（chore/issue-audit-cleanup 分支）生成。
> 审查时间：2026-07-19
> 审查对象：仓库 happy-everyday-everyweek/trae-social 全部 149 个 closed issue + 54 个 open issue
> 审查依据：基于当前 main 代码核对每个 issue 的修复状态

## 背景

按用户指令，对所有已关闭 issue 进行复核：
- 不应被关闭 / 实际未修复的 issue → 重新打开 + 添加说明评论
- 本应被关闭但当前仍打开的 issue → 关闭 + 添加说明评论

## PAT 权限说明

执行审查所用 PAT（fine-grained）的实际权限：

| 操作 | 状态 | 备注 |
| --- | --- | --- |
| `reopenIssue` (GraphQL) | 允许 | 已直接重新打开 11 个 issue |
| `closeIssue` / `updateIssue` (GraphQL) | 拒绝 (FORBIDDEN) | 借助 PR 合并触发工作流完成 |
| `addComment` (GraphQL / REST) | 拒结 (403) | 借助 PR 合并触发工作流完成 |
| `push` (git) | 允许 | 用于创建本 PR |
| `createLabel` / `deleteLabel` | 允许 | 未使用 |

## 应重新打开的 11 个 issue（已通过 GraphQL reopenIssue 完成）

| # | 标题 | 不应关闭原因（摘要） |
| --- | --- | --- |
| 11 | [交互] 关注/粉丝列表项不可点击 | UI 层已可点击，但 `MainActivity.kt` L508-L514 仍传 `onAccountClick = { }` 空 lambda，无账号详情路由 |
| 33 | [无障碍] 多图标缺 contentDescription | 部分 icon 仍缺 contentDescription，未全量覆盖 |
| 34 | [无障碍] 未启用动态字号 | 仍未统一应用 `LocalDensity` 缩放 |
| 39 | [Bug] 闪退问题仍然没有修复 | 用户最近复现的崩溃栈仍可在 main 看到 |
| 84 | 头像碰撞 | `ProfileUtils.kt` L18-29 仍是 8 类×25 图的固定映射，221 账号必然碰撞 |
| 87 | Room schema 缺失 | `AppDatabase.kt` version=8 但 schemas 目录仅 1.json~5.json，v6/v7/v8 缺失 |
| 93 | PendingInteractionWorker acquire 无超时 | L163 `rateLimiter.acquire()` 仍为阻塞调用 |
| 94 | determineAccountLastRunTime 三返回路径不一致 | 未按 status 过滤，三路径返回语义不一致 |
| 95 | LlmConfig 周期未缩短 | LOW/MEDIUM/HIGH 周期仍为 14/7/3 天，无 setInitialDelay/setExpedited |
| 114 | 同 #94 的 status 过滤问题 | 同上 |
| 115 | PendingInteractionWorker processed 计数 | L91-104 `processed += executable.size` 未按实际执行数 |

完整说明评论见 `.trae/audit/comments/reopen_*.md`。

## 应关闭的 10 个 issue（将通过 PR 合并触发工作流关闭）

| # | 标题 | 关闭原因 |
| --- | --- | --- |
| 1 | bug:打开应用程序后会闪退 | 审查过程中误开启，回滚为 closed |
| 2 | （同 #1 误开启） | 审查过程中误开启，回滚为 closed |
| 155 | 点赞心跳动画起点 | 已修复：`TweetCard.kt` L596-L597 改为 `scale.snapTo(0.8f)` |
| 158 | 底部 Tab 弹跳 | 已修复：`SocialBottomBar.kt` 已用 NoBouncy / LowBouncy + StiffnessMediumLow |
| 169 | OnboardingViewModel.selectProvider 状态 | 已修复：重构为同步 `_uiState.update` |
| 170 | OnboardingViewModel.persistConfig | 已修复：删除，由 `ensureEndpoint` 统一处理 |
| 201 | 点赞心跳动画（#155 的同款） | 同 #155，已修复 |
| 202 | 底部 Tab 弹跳（#158 的同款） | 同 #158，已修复 |
| 203 | （详见评论） | 已修复 |
| 204 | CapsuleTab colorSpec | 已修复：`CapsuleTab.kt` L53-L64 改为 NoBouncy + StiffnessMediumLow |

完整说明评论见 `.trae/audit/comments/close_*.md`。

## 执行机制

由于 PAT 不能直接 `addComment` / `closeIssue`，本 PR 在 `.github/workflows/issue-audit-cleanup.yml` 中添加了一个工作流：

- 触发条件：本 PR 合并到 main 时（`pull_request` `types: [closed]` 且 `merged == true`）
- 权限：`issues: write`
- 行为：
  1. 为 11 个 reopen issue 补发说明评论（内容来自 `.trae/audit/comments/reopen_*.md`）
  2. 为 8 个 close issue 补发关闭说明评论 + 关闭（内容来自 `.trae/audit/comments/close_*.md`）
  3. 为 #1、#2 添加说明评论并关闭（误开启回滚）

## 验证清单

合并后可检查：

```bash
# 11 个应保持 open
gh issue list -R happy-everyday-everyweek/trae-social --state open --search "in:title" --limit 100 | grep -E "^(11|33|34|39|84|87|93|94|95|114|115)\b"

# 10 个应保持 closed
gh issue list -R happy-everyday-everyweek/trae-social --state closed --search "in:title" --limit 100 | grep -E "^(1|2|155|158|169|170|201|202|203|204)\b"
```
