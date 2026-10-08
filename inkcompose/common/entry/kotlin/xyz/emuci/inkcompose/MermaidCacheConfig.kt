package xyz.emuci.inkcompose

/**
 * Mermaid 磁盘缓存全局配置。
 * 路径可由宿主应用（如 ViewModel）动态注入，未指定时默认使用系统临时缓存目录。
 */
object MermaidCacheConfig {
    fun setBaseDirectory(path: String) = platformMermaidSetBaseDirectory(path)
    fun getBaseDirectory(): String? = platformMermaidGetBaseDirectory()
    fun clearSessionCache(sessionKey: String) = platformMermaidClearSessionCache(sessionKey)
}

internal expect fun platformMermaidSetBaseDirectory(path: String)
internal expect fun platformMermaidGetBaseDirectory(): String?
internal expect fun platformMermaidClearSessionCache(sessionKey: String)
