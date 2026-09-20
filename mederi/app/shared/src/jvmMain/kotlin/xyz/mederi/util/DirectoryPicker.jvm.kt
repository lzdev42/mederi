package xyz.mederi.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFileChooser

actual suspend fun pickDirectory(title: String): String? = withContext(Dispatchers.IO) {
    val osName = System.getProperty("os.name", "").lowercase()
    val isMac = osName.contains("mac")

    println("[DirectoryPicker] Initiating directory pick on OS: $osName")

    try {
        if (isMac) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isVisible = true
            System.setProperty("apple.awt.fileDialogForDirectories", "false")

            val dir = dialog.directory
            val file = dialog.file
            println("[DirectoryPicker] macOS FileDialog returned dir=$dir, file=$file")

            if (dir != null && file != null) {
                val f = java.io.File(dir, file)
                val path = if (f.isDirectory) f.absolutePath else java.io.File(dir).absolutePath
                println("[DirectoryPicker] Picked path: $path")
                path
            } else if (dir != null) {
                val path = java.io.File(dir).absolutePath
                println("[DirectoryPicker] Picked dir path: $path")
                path
            } else {
                println("[DirectoryPicker] User cancelled dialog")
                null
            }
        } else {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = title
                isAcceptAllFileFilterUsed = false
            }
            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                val path = chooser.selectedFile?.absolutePath
                println("[DirectoryPicker] JFileChooser picked path: $path")
                path
            } else {
                println("[DirectoryPicker] JFileChooser cancelled")
                null
            }
        }
    } catch (e: Exception) {
        println("[DirectoryPicker] Exception during pickDirectory: ${e.message}")
        null
    }
}
