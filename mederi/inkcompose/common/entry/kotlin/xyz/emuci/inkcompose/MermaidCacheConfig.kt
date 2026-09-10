package xyz.emuci.inkcompose

/**
 * Mermaid 磁盘缓存全局配置。
 * 路径可由宿主应用（如 ViewModel 从 core 读取后）动态注入，默认自动展开 "~/.mederi"。
 */
expect object MermaidCacheConfig {
    fun setBaseDirectory(path: String)
    fun getBaseDirectory(): String?
}
