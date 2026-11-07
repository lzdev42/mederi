package xyz.mederi.office

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import xyz.mederi.tools.ToolName
import java.io.File

/**
 * Office 文档工具集（主代理专用）。
 *
 * 两个工具：
 * - [OfficeReadTool]：读 docx/xlsx/pptx → markdown 文本（AI 可读可改）。
 * - [OfficeWriteTool]：从 markdown 生成/覆盖 docx/xlsx（AI 改完后写回）。
 *
 * 路径校验与 FileSystemTools 共用 SandboxPaths / ExecutionPermissions；读全盘，越界写先批准。
 *
 * @param allowedDirectories 项目目录列表（绝对路径），写操作的作用域。
 */
class OfficeTools(
    private val allowedDirectories: List<String>,
    private val executionPermissions: xyz.mederi.tools.sandbox.ExecutionPermissions? = null,
) {

    @Serializable
    data class OfficeReadArgs(
        @LLMDescription("Office file path (.docx/.xlsx/.pptx), absolute or relative.")
        val path: String = ""
    )

    @Serializable
    data class OfficeWriteArgs(
        @LLMDescription("Office file path to write (.docx or .xlsx). Outside or protected writes request one-time user approval in Needs Approval mode.")
        val path: String = "",
        @LLMDescription(
            "Markdown content to write. Format:\n" +
            "- docx: #/##/### headings, - lists, |...| tables, plain paragraphs.\n" +
            "- xlsx: ## SheetName starts a sheet, |...| rows (first row = header).\n" +
            "The content fully replaces the file."
        )
        val content: String = ""
    )

    /** 读取 Office 文档，返回 markdown 文本。 */
    inner class OfficeReadTool : SimpleTool<OfficeReadArgs>(
        argsType = typeToken<OfficeReadArgs>(),
        name = ToolName.OFFICE_READ.value,
        description = "Read an Office document (.docx/.xlsx/.pptx) and convert it to markdown text."
    ) {
        override suspend fun execute(args: OfficeReadArgs): String {
            if (args.path.isBlank()) return "Error: path must not be empty."
            return try {
                val file = resolveForRead(args.path)
                if (!OfficeConverter.isSupported(file.absolutePath)) {
                    return "Error: unsupported format. Only .docx/.xlsx/.pptx are supported."
                }
                val markdown = OfficeConverter.toMarkdown(file.absolutePath)
                if (markdown.isBlank()) "(empty document)" else markdown
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "Error reading office file: ${e.message}"
            }
        }
    }

    /** 从 markdown 内容生成/覆盖 Office 文档。 */
    inner class OfficeWriteTool : SimpleTool<OfficeWriteArgs>(
        argsType = typeToken<OfficeWriteArgs>(),
        name = ToolName.OFFICE_WRITE.value,
        description = "Generate or overwrite an Office document (.docx/.xlsx) from markdown content. docx: #/##/### headings, - lists, | tables, paragraphs; xlsx: ## sheet names, | rows. The content fully replaces the file; outside or protected writes request one-time user approval in Needs Approval mode."
    ) {
        override suspend fun execute(args: OfficeWriteArgs): String {
            if (args.path.isBlank()) return "Error: path must not be empty."
            if (args.content.isBlank()) return "Error: content must not be empty."
            return try {
                val file = resolvePath(args.path).canonicalFile
                val ext = file.name.substringAfterLast('.', "").lowercase()
                if (ext !in listOf("docx", "xlsx")) return "Error: unsupported format for writing: .$ext (only .docx/.xlsx)"
                val grant = executionPermissions?.authorizeWrite("office_write", listOf(file.toPath()))
                if (grant == null) require(xyz.mederi.tools.sandbox.SandboxPaths(allowedDirectories,
                    emptyList()).allowsWrite(file.toPath())) {
                    "Writes outside sandbox require execution approval."
                }
                val conflict = xyz.mederi.tools.FileWriteRegistry.tryAcquire(listOf(file))
                if (conflict != null) return "Error: file is being modified by another tool: $file"
                try {
                    grant?.let { executionPermissions!!.checkGrant(it) }
                    if (file.exists()) {
                        val links = runCatching { java.nio.file.Files.getAttribute(file.toPath(), "unix:nlink") as Number }.getOrNull()
                        require(links == null || links.toInt() <= 1) { "Refusing to overwrite a hard-linked file: $file" }
                    }
                    file.parentFile?.mkdirs()
                    when (ext) {
                        "docx" -> OfficeConverter.writeDocx(file.absolutePath, args.content)
                        "xlsx" -> OfficeConverter.writeXlsx(file.absolutePath, args.content)
                    }
                } finally {
                    xyz.mederi.tools.FileWriteRegistry.release(listOf(file))
                }
                "Wrote ${file.name} (${args.content.length} chars markdown → $ext)"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "Error writing office file: ${e.message}"
            }
        }
    }

    // ==================== 路径解析（与 FileSystemTools 同逻辑） ====================

    private suspend fun resolveForRead(rawPath: String): File {
        val file = resolvePath(rawPath).canonicalFile
        executionPermissions?.checkRead(file.toPath())
        if (!file.exists()) throw IllegalArgumentException("Path does not exist: $rawPath")
        if (!file.isFile) throw IllegalArgumentException("Path is not a file: $rawPath")
        return file
    }

    private fun resolvePath(rawPath: String): File {
        val file = File(rawPath)
        if (file.isAbsolute) return file
        // 相对路径：在允许目录下查找
        for (dir in allowedDirectories) {
            val candidate = File(dir, rawPath)
            if (candidate.exists()) return candidate
        }
        // 都不存在时，相对于第一个允许目录
        return File(allowedDirectories.first(), rawPath)
    }
}
