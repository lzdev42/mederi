package xyz.emuci.inkcompose

import xyz.emuci.diagram.mermaid.AndroidMermaidDiskCache

actual object MermaidCacheConfig {
    actual fun setBaseDirectory(path: String) {
        AndroidMermaidDiskCache.setBaseDir(path)
    }

    actual fun getBaseDirectory(): String? = AndroidMermaidDiskCache.getBaseDir()

    actual fun clearSessionCache(sessionKey: String) {
        AndroidMermaidDiskCache.clearSession(sessionKey)
    }
}
