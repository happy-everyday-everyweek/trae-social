package com.trae.social.core.data.gallery

/**
 * 配图使用记录端口。
 *
 * 抽象自 Room 的 ImageUsageDao（Task 3 实现）。本任务（Task 6）只定义接口，
 * 由 [InMemoryImageUsagePort] 提供进程内默认实现以保证可编译运行；
 * Task 3 完成 ImageUsageDao 后，应在 core-data 中提供 Room 实现并替换默认绑定。
 *
 * 时间单位统一为 epoch 毫秒，便于跨时区处理。
 */
interface ImageUsagePort {

    /**
     * 返回指定账号在 [sinceMillis] 之后（含）使用过的配图 asset 路径集合。
     * 用于配图去重，避免 30 天内重复使用同一张图。
     */
    suspend fun recentlyUsedAssets(accountId: String, sinceMillis: Long): Set<String>

    /**
     * 记录一次配图使用。
     *
     * @param accountId 虚拟账号 ID
     * @param assetPath 配图 asset 路径，例如 "gallery/landscape/3.svg"
     * @param usedAtMillis 使用时间（epoch 毫秒）
     */
    suspend fun recordUsage(accountId: String, assetPath: String, usedAtMillis: Long)
}
