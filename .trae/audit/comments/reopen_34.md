## 重新打开：历史 Key 加密存储未实现

经审查，`feature-onboarding/src/main/java/com/trae/social/onboarding/KeyInputScreen.kt` 已实现：
- `detectProviderFromKey` 前缀识别（sk-ant-/sk-/AIza）
- `RECOMMENDED_MODELS` 模型下拉
- `PROVIDER_KEY_URLS` 官方获取 Key 链接
- 文案改为"通过 Android Keystore 加密存储"

但未完成 issue 建议的"加密保存历史 API Key 供重配时快速选择"功能：
- 全局 Grep `EncryptedSharedPreferences` 在 `KeyInputScreen` 中仅用于当前 Key 持久化，未维护历史 Key 列表
- 无 UI 让用户从历史 Key 中选择

请补全历史 Key 存储后再次关闭。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
