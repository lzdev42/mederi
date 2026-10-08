package xyz.emuci.inkcompose

import xyz.emuci.diagram.mermaid.AndroidMermaidDiskCache

internal actual fun platformMermaidSetBaseDirectory(path: String) {
    AndroidMermaidDiskCache.setBaseDir(path)
}

internal actual fun platformMermaidGetBaseDirectory(): String? = AndroidMermaidDiskCache.getBaseDir()

internal actual fun platformMermaidClearSessionCache(sessionKey: String) {
    AndroidMermaidDiskCache.clearSession(sessionKey)
}
