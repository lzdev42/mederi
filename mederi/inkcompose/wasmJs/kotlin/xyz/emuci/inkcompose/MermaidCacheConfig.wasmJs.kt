package xyz.emuci.inkcompose

actual object MermaidCacheConfig {
    actual fun setBaseDirectory(path: String) {}
    actual fun getBaseDirectory(): String? = null
    actual fun clearSessionCache(sessionKey: String) {}
}
