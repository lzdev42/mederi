package xyz.emuci.inkcompose

import xyz.emuci.diagram.mermaid.IosMermaidDiskCache

actual object MermaidCacheConfig {
    actual fun setBaseDirectory(path: String) {
        IosMermaidDiskCache.setBaseDir(path)
    }

    actual fun getBaseDirectory(): String? = IosMermaidDiskCache.getBaseDir()

    actual fun clearSessionCache(sessionKey: String) {
        IosMermaidDiskCache.clearSession(sessionKey)
    }
}
