package xyz.emuci.markdown.renderer.internal.util

/**
 * 跨平台互斥锁：保护 commonMain 中的共享可变缓存。
 *
 * - JVM / Android：对象监视器锁
 * - iOS (Kotlin/Native)：kotlin.concurrent.Lock
 * - JS / wasmJs：单线程事件循环天然串行，直接执行
 */
expect class CacheMutex() {
    fun <T> withLock(block: () -> T): T
}
