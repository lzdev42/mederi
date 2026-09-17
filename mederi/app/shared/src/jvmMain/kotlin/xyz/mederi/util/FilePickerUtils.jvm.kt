package xyz.mederi.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

actual suspend fun pickSaveFile(defaultName: String, extension: String): String? = withContext(Dispatchers.IO) {
    val osName = System.getProperty("os.name", "").lowercase()
    val isMac = osName.contains("mac")
    val defaultFileName = if (defaultName.endsWith(".$extension", ignoreCase = true)) defaultName else "$defaultName.$extension"

    try {
        if (isMac) {
            val dialog = FileDialog(null as Frame?, "导出文件", FileDialog.SAVE).apply {
                file = defaultFileName
                isVisible = true
            }
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) {
                val finalName = if (file.endsWith(".$extension", ignoreCase = true)) file else "$file.$extension"
                File(dir, finalName).absolutePath
            } else null
        } else {
            val chooser = JFileChooser().apply {
                dialogTitle = "导出文件"
                selectedFile = File(defaultFileName)
                fileFilter = FileNameExtensionFilter("${extension.uppercase()} 文件 (*.$extension)", extension)
            }
            val result = chooser.showSaveDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                val selected = chooser.selectedFile ?: return@withContext null
                val finalName = if (selected.name.endsWith(".$extension", ignoreCase = true)) selected.name else "${selected.name}.$extension"
                File(selected.parentFile, finalName).absolutePath
            } else null
        }
    } catch (e: Exception) {
        println("[FilePickerUtils] Exception during pickSaveFile: ${e.message}")
        null
    }
}

actual suspend fun writeTextToFile(filePath: String, text: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        true
    } catch (e: Exception) {
        println("[FilePickerUtils] Exception during writeTextToFile: ${e.message}")
        false
    }
}
