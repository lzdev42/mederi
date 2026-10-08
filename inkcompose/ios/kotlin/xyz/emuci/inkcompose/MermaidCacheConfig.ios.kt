package xyz.emuci.inkcompose

import xyz.emuci.diagram.mermaid.IosMermaidDiskCache

internal actual fun platformMermaidSetBaseDirectory(path: String) {
    IosMermaidDiskCache.setBaseDir(path)
}

internal actual fun platformMermaidGetBaseDirectory(): String? = IosMermaidDiskCache.getBaseDir()

internal actual fun platformMermaidClearSessionCache(sessionKey: String) {
    IosMermaidDiskCache.clearSession(sessionKey)
}
