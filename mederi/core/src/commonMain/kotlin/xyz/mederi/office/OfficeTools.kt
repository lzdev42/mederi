package xyz.mederi.office

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Office 文档工具集（主代理专用）。
 *
 * 两个工具：
 * - [OfficeReadTool]：读 docx/xlsx/pptx → markdown 文本（AI 可读可改）。
 * - [OfficeWriteTool]：从 markdown 生成/覆盖 docx/xlsx（AI 改完后写回）。
 *
 * 路径校验与 FileSystemTools 一致：读全盘放行，写限项目目录。
 *
 * @param allowedDirectories 项目目录列表（绝对路径），写操作的作用域。
 */
class OfficeTools(
    private val allowedDirectories: List<String>
) {

    @Serializable
    data class OfficeReadArgs(
        @LLMDescription("Office file path (.docx/.xlsx/.pptx), absolute or relative.")
        val path: String = ""
    )

    @Serializable
    data class OfficeWriteArgs(
        @LLMDescription("Office file path to write (.docx or .xlsx). Must be inside project directories.")
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
        name = "office_read",
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
                "Error reading office file: ${e.message}"
            }
        }
    }

    /** 从 markdown 内容生成/覆盖 Office 文档。 */
    inner class OfficeWriteTool : SimpleTool<OfficeWriteArgs>(
        argsType = typeToken<OfficeWriteArgs>(),
        name = "office_write",
        description = "Generate or overwrite an Office document (.docx/.xlsx) from markdown content. docx: #/##/### headings, - lists, | tables, paragraphs; xlsx: ## sheet names, | rows. The content fully replaces the file; the path must be inside project directories."
    ) {
        override suspend fun execute(args: OfficeWriteArgs): String {
            if (args.path.isBlank()) return "Error: path must not be empty."
            if (args.content.isBlank()) return "Error: content must not be empty."
            return try {
                val file = resolveForWrite(args.path)
                val ext = file.name.substringAfterLast('.', "").lowercase()
                when (ext) {
                    "docx" -> OfficeConverter.writeDocx(file.absolutePath, args.content)
                    "xlsx" -> OfficeConverter.writeXlsx(file.absolutePath, args.content)
                    else -> return "Error: unsupported format for writing: .$ext (only .docx/.xlsx)"
                }
                "Wrote ${file.name} (${args.content.length} chars markdown → $ext)"
            } catch (e: Exception) {
                "Error writing office file: ${e.message}"
            }
        }
    }

    // ==================== 路径解析（与 FileSystemTools 同逻辑） ====================

    private fun resolveForRead(rawPath: String): File {
        val file = resolvePath(rawPath)
        if (!file.exists()) throw IllegalArgumentException("Path does not exist: $rawPath")
        if (!file.isFile) throw IllegalArgumentException("Path is not a file: $rawPath")
        return file
    }

    private fun resolveForWrite(rawPath: String): File {
        val file = resolvePath(rawPath)
        val absolute = file.absoluteFile.normalize()
        // 写校验：必须在项目目录内
        val inside = allowedDirectories.any { dir ->
            val dirFile = File(dir).absoluteFile.normalize()
            absolute.canonicalPath.startsWith(dirFile.canonicalPath)
        }
        if (!inside) {
            throw IllegalArgumentException(
                "Path '$rawPath' is outside project directories. " +
                    "Writes are restricted to project directories (code-enforced)."
            )
        }
        // 确保父目录存在
        val parent = absolute.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        return absolute
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
