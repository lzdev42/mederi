package xyz.mederi.util

actual fun openUrl(url: String) {
    // Android: would use Intent.ACTION_VIEW; stub for now
}

actual fun openFile(path: String): Boolean {
    // Android: would use FileProvider + Intent.ACTION_VIEW; stub for now
    return false
}

// 遥控端：文件不在本地，不实现；desktop jvm 是唯一实际消费方
actual fun defaultAppNameFor(path: String): String? = null
actual fun revealInFolder(path: String): Boolean = false
