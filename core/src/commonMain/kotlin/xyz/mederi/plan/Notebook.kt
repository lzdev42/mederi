package xyz.mederi.plan

import java.io.File

class Notebook(private val projectDirectories: List<String>) {

    fun append(entry: String) {
        // 写路径自愈：老项目缺 .mederi 时自动在主目录下补齐
        val mederiDir = ensureMederiDir(projectDirectories) ?: return
        val file = File(mederiDir, "notebook.md")
        file.parentFile.mkdirs()
        if (!file.exists()) file.writeText("# 工作日志\n\n")
        file.appendText("\n$entry\n")
    }
}
