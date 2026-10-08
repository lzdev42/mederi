package xyz.emuci.markdown.renderer.internal.util

/** wasmJs 单线程事件循环天然串行，无需同步原语。 */
internal actual fun createPlatformCacheLock(): Any = Any()

internal actual fun <T> withPlatformCacheLock(lock: Any, block: () -> T): T = block()
