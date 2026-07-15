package com.trae.social.core.profiling.feedback

import com.trae.social.core.data.repository.ConfigRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 画像读缓存（#146 第四层性能优化）。
 *
 * TTL 30s，避免业务侧高频读打 DB；覆盖 / 快照 / 版本激活变更时主动 [invalidate]。
 */
@Singleton
class ProfileCache @Inject constructor(
    private val configRepository: ConfigRepository,
) {
    private val store = HashMap<String, CacheEntry>()
    private val lock = Any()

    fun get(key: String): Any? {
        synchronized(lock) {
            val entry = store[key] ?: return null
            if (System.currentTimeMillis() - entry.createdAt > ttlMs) {
                store.remove(key)
                return null
            }
            return entry.value
        }
    }

    fun put(key: String, value: Any?) {
        synchronized(lock) {
            if (value == null) {
                store.remove(key)
            } else {
                store[key] = CacheEntry(value, System.currentTimeMillis())
            }
        }
    }

    /** 覆盖 / 快照 / 版本激活变更时调用，清空全部缓存条目。 */
    fun invalidate() {
        synchronized(lock) { store.clear() }
    }

    private val ttlMs: Long get() = ConfigRepository.PROFILE_CACHE_TTL_MS

    private data class CacheEntry(val value: Any, val createdAt: Long)
}
