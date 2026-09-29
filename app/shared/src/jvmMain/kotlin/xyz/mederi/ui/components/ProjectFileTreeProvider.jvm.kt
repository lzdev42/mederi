package xyz.mederi.ui.components

import java.io.File

/** 桌面 java.io 实现：跳过隐藏文件与 .git；目录优先、名称排序；懒列子项。 */
class ProjectFileTreeProviderJvm(private val root: File) : ProjectFileTreeProvider {
    override fun listChildren(dirPath: String): List<FileNode> {
        val dir = File(dirPath).takeIf { it.isDirectory } ?: return emptyList()
        return dir.listFiles()?.asSequence()
            ?.filter { !it.name.startsWith(".") && it.name != ".git" }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.map { FileNode(name = it.name, path = it.absolutePath, isDirectory = it.isDirectory) }
            ?.toList() ?: emptyList()
    }

    override fun readText(path: String): String? = runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun rootNode(): FileNode = FileNode(name = root.name.ifBlank { root.absolutePath }, path = root.absolutePath, isDirectory = true)
}