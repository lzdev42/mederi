package xyz.mederi.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** 文件树节点。isDirectory 决定渲染文件夹/文件行。 */
data class FileNode(val name: String, val path: String, val isDirectory: Boolean)

/** 可注入的项目文件树数据源：桌面端提供 java.io 实现，其他平台为 null（树 Tab 显示不支持提示）。 */
interface ProjectFileTreeProvider {
    fun rootNode(): FileNode
    fun listChildren(dirPath: String): List<FileNode>
    fun readText(path: String): String?
}

val LocalProjectFileTreeProvider = staticCompositionLocalOf<ProjectFileTreeProvider?> { null }