package xyz.emuci.markdown.renderer.internal.util

import kotlin.concurrent.AtomicInt

/**
 * Kotlin/Native 无 kotlin.concurrent.Lock，用 stdlib 稳定的 AtomicInt CAS 自旋实现。
 * CacheMutex 只保护缓存 map 的微秒级读写，自旋开销可忽略。
 */
internal actual fun createPlatformCacheLock(): Any = AtomicInt(0)

internal actual fun <T> withPlatformCacheLock(lock: Any, block: () -> T): T {
    val state = lock as AtomicInt
    while (!state.compareAndSet(0, 1)) {
        // 自旋等待；临界区极短，不引入 pthread 依赖
    }
    try {
        return block()
    } finally {
        state.value = 0
    }
}
