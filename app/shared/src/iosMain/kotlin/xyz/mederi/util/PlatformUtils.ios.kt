package xyz.mederi.util

actual fun openUrl(url: String) {
    // iOS: would use UIApplication.shared.open; stub for now
}

actual fun openFile(path: String): Boolean {
    // iOS: 本地文件打开需要 UIDocumentInteractionController；暂无场景，stub
    return false
}

// 遥控端：文件不在本地，不实现；desktop jvm 是唯一实际消费方
actual fun defaultAppNameFor(path: String): String? = null
actual fun revealInFolder(path: String): Boolean = false
