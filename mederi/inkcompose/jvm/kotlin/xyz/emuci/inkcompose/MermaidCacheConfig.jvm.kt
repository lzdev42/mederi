package xyz.emuci.inkcompose

import xyz.emuci.diagram.mermaid.MermaidDiskCache

actual object MermaidCacheConfig {
    actual fun setBaseDirectory(path: String) {
        MermaidDiskCache.setBaseDir(path)
    }

    actual fun getBaseDirectory(): String? = MermaidDiskCache.getBaseDir()
}
