package com.trae.social.core.data

/**
 * #220：账号 ID 集中常量。
 *
 * 此前「当前用户账号 ID = `"user-self"`」在 7+ 文件中以 4 种不同命名重复定义
 * （`LOG_ACCOUNT_ID` / `SELF_ID` / `AUTHOR_SELF` / `USER_SELF_ID`，外加直接内联字符串），
 * 每个定义都带「与 XXX 一致」的注释，说明开发者已意识到需对齐但未统一。任一处拼写错误
 * 或修改字符串字面量（如从 `"user-self"` 改为 `"self"`）会导致数据库查询、互动记录、
 * 评论作者归属错乱。
 *
 * 抽到此 object 后，所有模块统一引用 [USER_SELF_ID]，移除各 ViewModel / Worker / Seeder
 * 内的私有 `const val`。命名沿用 `USER_SELF_ID`（与原 `PersonaSeeder.USER_SELF_ID` 一致，
 * 向后兼容性最好）。
 */
object AccountIds {
    /**
     * 当前用户账号 ID：固定为 `"user-self"`。
     *
     * - 由 [com.trae.social.core.data.seed.PersonaSeeder.ensureUserSelfAccount] 首次导入时
     *   插入 `id="user-self"`、`isVirtual=false` 的真实账号（IMPL-1/IMPL-3/IMPL-12 修复）
     * - 用户发布的推文 `authorId = USER_SELF_ID`
     * - 用户的点赞 / 评论 / 转发 / 收藏 `accountId = USER_SELF_ID`
     * - 关注关系 `followerId = USER_SELF_ID`（关注虚拟账号）
     * - 调度日志 `accountId = USER_SELF_ID`（满足外键约束，无业务关联时也用此值）
     */
    const val USER_SELF_ID: String = "user-self"
}
