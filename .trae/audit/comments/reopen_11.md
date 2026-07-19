## 重新打开：账号详情入口仍未接入

经审查当前代码，`feature-profile/src/main/java/com/trae/social/profile/FollowListScreen.kt` 中的 `FollowAccountRow` 已添加整行 `Modifier.clickable { onAccountClick() }` 与关注按钮（UI 层可点击与关注操作已实现），但导航闭环未打通：

- `app/src/main/java/com/trae/social/app/MainActivity.kt` L508-L514 中 `FollowListScreen` 调用处仍传入 `onAccountClick = { }` 空 lambda，并注释"#11：账号详情页待接入，预留回调结构"——点击账号无任何反应。
- `app/src/main/java/com/trae/social/app/ui/AppRoutes.kt` 中无账号详情路由定义，无对应 `composable { ... }` 入口。

issue 标题明确要求"账号详情入口"，建议在 `AppRoutes` 中新增 `ACCOUNT_DETAIL = "account/{accountId}"` 路由、在 `MainActivity` 中接入 `ProfileScreen(targetAccountId)` 或独立 `AccountDetailScreen`，并将 `onAccountClick` 接到 `navController.navigate(AppRoutes.accountDetail(account.id))`。请完成接入后再次关闭。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
