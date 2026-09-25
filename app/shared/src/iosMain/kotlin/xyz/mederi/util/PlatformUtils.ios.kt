package xyz.mederi.util

actual fun openUrl(url: String) {
    // iOS: would use UIApplication.shared.open; stub for now
}

actual fun openFile(path: String) {
    // iOS: 本地文件打开需要 UIDocumentInteractionController；暂无场景，stub
}
