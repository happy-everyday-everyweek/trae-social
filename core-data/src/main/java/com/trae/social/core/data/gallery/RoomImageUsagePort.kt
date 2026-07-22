package com.trae.social.core.data.gallery

import com.trae.social.core.data.dao.ImageUsageDao
import com.trae.social.core.data.entity.ImageUsageEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ImageUsagePort] 的 Room 持久化实现。
 *
 * 取代 [InMemoryImageUsagePort]：配图去重记录持久化至 Room `image_usages` 表，
 * 进程重启后 30 天去重窗口仍然有效（RISK-14 / spec 配图去重 Scenario）。
 *
 * imageHash 字段直接使用 assetPath 作为唯一标识（asset 路径本身是稳定哈希）。
 *
 * 线程安全：Room 内部保证事务原子性，无需额外锁。
 */
@Singleton
class RoomImageUsagePort @Inject constructor(
    private val imageUsageDao: ImageUsageDao,
) : ImageUsagePort {

    override suspend fun recentlyUsedAssets(accountId: String, sinceMillis: Long): Set<String> {
        return imageUsageDao.getUsedHashes(accountId, sinceMillis).toSet()
    }

    override suspend fun recordUsage(accountId: String, assetPath: String, usedAtMillis: Long) {
        imageUsageDao.insert(
            ImageUsageEntity(
                accountId = accountId,
                imageHash = assetPath,
                usedAt = usedAtMillis,
            )
        )
    }
}
