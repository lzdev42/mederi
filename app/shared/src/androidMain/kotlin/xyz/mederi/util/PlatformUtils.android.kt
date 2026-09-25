package xyz.mederi.util

actual fun openUrl(url: String) {
    // Android: would use Intent.ACTION_VIEW; stub for now
}

actual fun openFile(path: String) {
    // Android: would use FileProvider + Intent.ACTION_VIEW; stub for now
}
