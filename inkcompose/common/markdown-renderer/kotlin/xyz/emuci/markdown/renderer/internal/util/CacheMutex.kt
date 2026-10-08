package xyz.emuci.markdown.renderer.internal.util

/**
 * 跨平台互斥锁：保护 commonMain 中的共享可变缓存。
 *
 * - JVM / Android：对象监视器锁
 * - iOS (Kotlin/Native)：kotlin.concurrent.Lock
 * - JS / wasmJs：单线程事件循环天然串行，直接执行
 */
class CacheMutex {
    private val lock: Any = createPlatformCacheLock()
    fun <T> withLock(block: () -> T): T = withPlatformCacheLock(lock, block)
}

internal expect fun createPlatformCacheLock(): Any
internal expect fun <T> withPlatformCacheLock(lock: Any, block: () -> T): T
