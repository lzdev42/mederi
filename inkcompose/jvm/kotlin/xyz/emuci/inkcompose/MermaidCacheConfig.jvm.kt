package xyz.emuci.inkcompose

import xyz.emuci.diagram.mermaid.MermaidDiskCache

internal actual fun platformMermaidSetBaseDirectory(path: String) {
    MermaidDiskCache.setBaseDir(path)
}

internal actual fun platformMermaidGetBaseDirectory(): String? = MermaidDiskCache.getBaseDir()

internal actual fun platformMermaidClearSessionCache(sessionKey: String) {
    MermaidDiskCache.clearSession(sessionKey)
}
