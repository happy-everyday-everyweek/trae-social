package com.trae.social.core.scheduler

/**
 * 调度层共享常量（#310）。
 *
 * 此前 [POSTS_PER_WINDOW] 在 [SchedulerInitializer] 与
 * [com.trae.social.core.scheduler.work.TweetGenerationWorker] 中各自私有定义且
 * 附带"与 XXX 保持一致"注释，任一处修改需同步多处。抽到此 object 后统一引用。
 */
object SchedulerConstants {
    /**
     * 每个活跃窗内允许发布的推文数上限（spec 默认 2 条/窗）。
     *
     * 由 [SchedulerInitializer] 构建 ScheduleRule 时传入，[TweetGenerationWorker]
     * 执行时据此判断窗内是否已满。两处必须一致，否则会出现"规则允许 N 条但 Worker
     * 只生成 M 条"的不一致。
     */
    const val POSTS_PER_WINDOW: Int = 2
}
