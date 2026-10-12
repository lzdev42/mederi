package xyz.mederi.browser

import xyz.kbrowser.webview.KBFileDialogCallback
import xyz.kbrowser.webview.KBFileDialogMode
import xyz.kbrowser.webview.KBFileDialogRequest
import xyz.kbrowser.webview.KBWebView
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** 向 WebView 注入 CSS zoom。 */
internal fun applyZoomToWebView(webView: KBWebView, zoomPercent: Int) {
    webView.evaluateJavascript(
        "if (document.documentElement) { document.documentElement.style.zoom = '${zoomPercent}%'; }"
    )
}

/** 在 AWT 事件派发线程弹出系统原生文件选择器，并保证回调最终被触发。 */
internal fun showNativeFileDialog(request: KBFileDialogRequest, callback: KBFileDialogCallback) {
    SwingUtilities.invokeLater {
        var resolved = false
        try {
            when (request.mode) {
                KBFileDialogMode.OPEN_FOLDER -> {
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        if (request.title.isNotBlank()) {
                            dialogTitle = request.title
                        }
                    }
                    val result = chooser.showOpenDialog(null)
                    if (result == JFileChooser.APPROVE_OPTION && chooser.selectedFile != null) {
                        resolved = true
                        callback.selectFiles(listOf(chooser.selectedFile.absolutePath))
                    }
                }

                KBFileDialogMode.SAVE,
                KBFileDialogMode.OPEN,
                KBFileDialogMode.OPEN_MULTIPLE -> {
                    val awtMode = if (request.mode == KBFileDialogMode.SAVE) FileDialog.SAVE else FileDialog.LOAD
                    val dialog = FileDialog(null as Frame?, request.title, awtMode).apply {
                        isMultipleMode = request.mode == KBFileDialogMode.OPEN_MULTIPLE
                        if (request.defaultFilePath.isNotBlank()) {
                            file = request.defaultFilePath
                        }
                    }
                    dialog.isVisible = true
                    val selectedFiles: Array<File> = dialog.files
                    if (selectedFiles.isNotEmpty()) {
                        resolved = true
                        callback.selectFiles(selectedFiles.map { it.absolutePath })
                    } else if (dialog.file != null) {
                        val path = File(dialog.directory ?: "", dialog.file).absolutePath
                        resolved = true
                        callback.selectFiles(listOf(path))
                    }
                }
            }
        } catch (e: Throwable) {
            println("[JcefBrowserHost] Native file dialog failed: ${e.message}")
        } finally {
            if (!resolved) {
                runCatching { callback.cancel() }
            }
        }
    }
}
