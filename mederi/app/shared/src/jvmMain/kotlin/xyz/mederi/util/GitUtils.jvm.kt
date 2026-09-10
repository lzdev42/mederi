package xyz.mederi.util

import java.io.File

actual fun getGitBranch(directoryPath: String): String? {
    try {
        val dir = File(directoryPath)
        if (!dir.exists() || !dir.isDirectory) return null

        val gitItem = File(dir, ".git")
        if (!gitItem.exists()) return null

        val headFile: File? = if (gitItem.isDirectory) {
            File(gitItem, "HEAD")
        } else if (gitItem.isFile) {
            val text = gitItem.readText().trim()
            if (text.startsWith("gitdir:")) {
                val gitDirPath = text.substringAfter("gitdir:").trim()
                val gitDir = if (File(gitDirPath).isAbsolute) {
                    File(gitDirPath)
                } else {
                    File(dir, gitDirPath)
                }
                File(gitDir, "HEAD")
            } else null
        } else null

        if (headFile != null && headFile.exists() && headFile.isFile) {
            val content = headFile.readText().trim()
            val branch = when {
                content.startsWith("ref: refs/heads/") -> content.removePrefix("ref: refs/heads/").trim()
                content.startsWith("ref: refs/tags/") -> content.removePrefix("ref: refs/tags/").trim()
                content.length >= 7 -> content.take(7)
                else -> null
            }
            println("[GitUtils] Detected git branch for $directoryPath: $branch")
            return branch
        }
    } catch (e: Exception) {
        println("[GitUtils] Failed to get git branch for $directoryPath: ${e.message}")
    }
    return null
}
