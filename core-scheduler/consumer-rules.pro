# core-scheduler 消费者 ProGuard 规则（IMPL-40）
# 被 app 模块消费，确保 release 构建时 @HiltWorker 类及 AssistedInject 构造函数不被混淆。

# @HiltWorker: 保留 Worker 类及 @AssistedInject 构造函数
-keep @androidx.hilt.work.HiltWorker class * { <init>(...); }
-keep class com.trae.social.core.scheduler.work.** { *; }
