## 重新打开：v6/v7/v8 schema JSON 仍缺失

经审查，`core-data/src/main/java/com/trae/social/core/data/db/AppDatabase.kt` L73 中 `@Database version = 8` 且 `exportSchema = true` 仍保留，但 `core-data/schemas/com.trae.social.core.data.db.AppDatabase/` 目录下仅有 `1.json` ~ `5.json`，**6.json、7.json、8.json 均缺失**。

原 issue 报告 v6 schema 缺失；当前问题反而扩大——v7（#146 新增 6 张表）与 v8（#227 删除 authorId 单列索引）的 schema JSON 也未提交。CI 迁移验证无法运行，违反 exportSchema=true 契约。

请在本地执行 `./gradlew :core-data:assembleDebug` 生成 6/7/8.json 后提交，或显式关闭 exportSchema。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
