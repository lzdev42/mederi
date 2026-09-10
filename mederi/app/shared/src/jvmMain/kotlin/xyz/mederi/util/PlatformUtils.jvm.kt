package xyz.mederi.util

import java.awt.Desktop
import java.io.File
import java.net.URI

actual fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (_: Exception) {
    }
}

actual fun openFile(path: String) {
    try {
        val file = File(path)
        if (file.exists() && Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
        }
    } catch (_: Exception) {
    }
}
