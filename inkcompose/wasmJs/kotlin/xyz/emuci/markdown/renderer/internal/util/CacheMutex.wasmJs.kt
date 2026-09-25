package xyz.emuci.markdown.renderer.internal.util

/** wasmJs 单线程事件循环天然串行，无需同步原语。 */
actual class CacheMutex actual constructor() {
    actual fun <T> withLock(block: () -> T): T = block()
}
