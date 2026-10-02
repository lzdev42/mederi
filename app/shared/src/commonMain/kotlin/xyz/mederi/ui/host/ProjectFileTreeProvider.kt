package xyz.mederi.ui.host

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 宿主能力接口（分层归位 2026-10）：本包只放「宿主注入给 UI 的能力接口」，
 * 与 `ui/browser/UiBrowserHost` 同性质——数据层（AppState/VM）可以依赖本包，
 * 但不得反向指向 View 组件层（`ui/components`）。
 */
/** 文件树节点。isDirectory 决定渲染文件夹/文件行。 */
data class FileNode(val name: String, val path: String, val isDirectory: Boolean)

/** 可注入的项目文件树数据源：桌面端提供 java.io 实现，其他平台为 null（树 Tab 显示不支持提示）。 */
interface ProjectFileTreeProvider {
    fun rootNode(): FileNode
    fun listChildren(dirPath: String): List<FileNode>
    fun readText(path: String): String?
}

val LocalProjectFileTreeProvider = staticCompositionLocalOf<ProjectFileTreeProvider?> { null }
