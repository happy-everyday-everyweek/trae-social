## 关闭：已修复

经审查，`feature-onboarding/src/main/java/com/trae/social/onboarding/OnboardingViewModel.kt` L122-L133 的 `selectProvider` 已重构为同步 `_uiState.update`：单次状态更新中重置 selectedProvider/baseUrl/model/apiKey/testStatus/pendingEndpointId，无异步 backfill 覆盖用户编辑。多端点架构下用户在 KeyInputScreen 中编辑的 baseUrl/model 不会被异步回填覆盖。满足 issue 全部 acceptance criteria。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
