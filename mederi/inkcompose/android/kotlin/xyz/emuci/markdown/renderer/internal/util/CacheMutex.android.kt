package xyz.emuci.markdown.renderer.internal.util

actual class CacheMutex actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}
