package com.trae.social.data.gallery

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ImageUsagePort] 的进程内默认实现。
 *
 * 仅用于 Task 6 阶段保证图库可独立运行；Task 3 完成 Room ImageUsageDao 后
 * 应替换为持久化实现（在 di 模块中替换 [ImageUsagePort] 的绑定）。
 *
 * 线程安全：使用 ConcurrentHashMap + 锁保护 Set 写入。
 */
@Singleton
class InMemoryImageUsagePort @Inject constructor() : ImageUsagePort {

    // key: accountId, value: mutable set of "<assetPath>@<usedAtMillis>"
    private val store = ConcurrentHashMap<String, MutableSet<String>>()

    override suspend fun recentlyUsedAssets(accountId: String, sinceMillis: Long): Set<String> {
        val entries = store[accountId] ?: return emptySet()
        val result = mutableSetOf<String>()
        for (entry in entries) {
            val idx = entry.lastIndexOf('@')
            if (idx < 0) continue
            val path = entry.substring(0, idx)
            val ts = entry.substring(idx + 1).toLongOrNull() ?: continue
            if (ts >= sinceMillis) {
                result.add(path)
            }
        }
        return result
    }

    override suspend fun recordUsage(accountId: String, assetPath: String, usedAtMillis: Long) {
        val set = store.computeIfAbsent(accountId) {
            java.util.Collections.synchronizedSet(mutableSetOf())
        }
        synchronized(set) {
            set.add("$assetPath@$usedAtMillis")
        }
    }
}
