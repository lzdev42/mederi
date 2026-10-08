package xyz.emuci.markdown.renderer.internal.util

internal actual fun createPlatformCacheLock(): Any = Any()

internal actual fun <T> withPlatformCacheLock(lock: Any, block: () -> T): T = synchronized(lock) { block() }
