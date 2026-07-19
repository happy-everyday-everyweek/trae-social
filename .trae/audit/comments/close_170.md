## 关闭：已修复

经审查，`feature-onboarding/src/main/java/com/trae/social/onboarding/OnboardingViewModel.kt` L210-L286 已重构：原 `persistConfig` 已删除，由 `ensureEndpoint` 统一处理。`ensureEndpoint` 调用 `configRepository.updateEndpoint` 写入端点表 + EncryptedSharedPreferences API Key，对空字段也写入（不再 skip），可清除已保存的 API Key/Base URL/Model。满足 issue 全部 acceptance criteria。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
