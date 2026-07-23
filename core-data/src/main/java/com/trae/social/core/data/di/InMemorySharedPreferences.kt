package com.trae.social.core.data.di

import android.content.SharedPreferences

/**
 * #301：纯内存 [SharedPreferences] 实现，作为 EncryptedSharedPreferences 创建失败时的安全回退。
 *
 * 此前失败回退到普通 [SharedPreferences]（明文落盘），导致 API Key 以明文写入磁盘文件，
 * 在 root 设备或备份导出场景下泄漏。本实现仅在内存保存键值，进程结束即消失，
 * 保证密钥绝不落盘明文。
 *
 * 代价：用户需在下次启动重新输入 API Key（Keystore 损坏本就是异常状态，可接受）。
 * 安全收益：消除"静默回退明文"这一 P0 级密钥泄漏路径。
 *
 * 仅实现 [SharedPreferences] 接口所需方法，行为与系统实现一致（除不持久化外）。
 * 线程安全：通过 [synchronized] 保护内部 map，与系统 SharedPreferences 的单线程写约束对齐。
 */
internal class InMemorySharedPreferences : SharedPreferences {

    private val store = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = synchronized(store) { store.toMap() }

    override fun getString(key: String, defValue: String?): String? =
        synchronized(store) { store[key] as? String ?: defValue }

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        synchronized(store) { (store[key] as? Set<*>)?.mapTo(mutableSetOf()) { it as String } ?: defValues }

    override fun getInt(key: String, defValue: Int): Int =
        synchronized(store) { (store[key] as? Int) ?: defValue }

    override fun getLong(key: String, defValue: Long): Long =
        synchronized(store) { (store[key] as? Long) ?: defValue }

    override fun getFloat(key: String, defValue: Float): Float =
        synchronized(store) { (store[key] as? Float) ?: defValue }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        synchronized(store) { (store[key] as? Boolean) ?: defValue }

    override fun contains(key: String): Boolean = synchronized(store) { store.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearPending = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            pending[key] = values
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = REMOVAL_MARKER
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearPending = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            val changedKeys = mutableListOf<String>()
            synchronized(store) {
                if (clearPending) {
                    changedKeys += store.keys
                    store.clear()
                    clearPending = false
                }
                for ((key, value) in pending) {
                    if (value === REMOVAL_MARKER) {
                        if (store.remove(key) != null) changedKeys += key
                    } else {
                        store[key] = value
                        changedKeys += key
                    }
                }
                pending.clear()
            }
            val snapshot = synchronized(listeners) { listeners.toList() }
            for (key in changedKeys) {
                snapshot.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, key) }
            }
        }
    }

    private companion object {
        // 用私有哨兵对象标记 remove，与 null 值区分（putString(key, null) 等价 remove）
        private val REMOVAL_MARKER = Any()
    }
}
