package xyz.emuci.markdown.renderer.internal.util

/**
 * 通用有界 LRU 缓存(主线程专用,无锁)。
 *
 * 命中时 remove+put(均 O(1))实现访问序提升,淘汰取插入序 eldest。
 * 各 inline 布局缓存共用此实现,避免每处重复维护 LinkedHashMap 样板。
 */
internal class LruCache<K, V>(
    private val maxEntries: Int,
    initialCapacity: Int = maxEntries.coerceAtMost(256),
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, got $maxEntries" }
    }

    private val entries = LinkedHashMap<K, V>(initialCapacity, 0.75f)

    fun get(key: K): V? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    fun put(key: K, value: V) {
        if (entries.size >= maxEntries && key !in entries) {
            evictEldest()
        }
        entries[key] = value
    }

    inline fun getOrPut(key: K, compute: () -> V): V {
        get(key)?.let { return it }
        val value = compute()
        put(key, value)
        return value
    }

    fun clear() {
        entries.clear()
    }

    private fun evictEldest() {
        val eldest = entries.keys.firstOrNull() ?: return
        entries.remove(eldest)
    }
}
