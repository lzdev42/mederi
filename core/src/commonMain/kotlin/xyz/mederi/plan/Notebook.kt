package xyz.mederi.plan

import java.io.File

class Notebook(
    private val projectDirectories: List<String>,
    private val artifactRoot: String? = null
) {

    fun append(entry: String): Boolean {
        // 写路径自愈：老项目缺 .mederi 时自动在主目录下补齐
        val mederiDir = if (artifactRoot != null) {
            File(artifactRoot).apply { mkdirs() }
        } else {
            ensureMederiDir(projectDirectories) ?: return false
        }
        val file = File(mederiDir, "notebook.md")
        file.parentFile?.mkdirs()
        if (!file.exists()) file.writeText("# 工作日志\n\n")
        file.appendText("\n$entry\n")
        return true
    }
}
