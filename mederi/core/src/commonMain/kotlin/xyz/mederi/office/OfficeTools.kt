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
        @LLMDescription("要读取的 Office 文件路径（.docx/.xlsx/.pptx）。可以是绝对路径或相对路径。")
        val path: String = ""
    )

    @Serializable
    data class OfficeWriteArgs(
        @LLMDescription("要写入的 Office 文件路径（.docx 或 .xlsx）。必须是项目目录内的路径。")
        val path: String = "",
        @LLMDescription(
            "要写入的 markdown 内容。格式要求：\n" +
            "- docx：用 #/##/### 表示标题，- 表示列表，|...|... 表示表格，普通段落直接写。\n" +
            "- xlsx：用 ## SheetName 表示 sheet，|...|... 表示表格行（第一行为表头）。\n" +
            "内容会完整替换原文件。"
        )
        val content: String = ""
    )

    /** 读取 Office 文档，返回 markdown 文本。 */
    inner class OfficeReadTool : SimpleTool<OfficeReadArgs>(
        argsType = typeToken<OfficeReadArgs>(),
        name = "office_read",
        description = "读取 Office 文档（.docx/.xlsx/.pptx）并转换为 markdown 文本。" +
            "用于查看 Word/Excel/PowerPoint 文件内容。读后可用 office_write 写回修改。"
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
        description = "从 markdown 内容生成或覆盖 Office 文档（.docx/.xlsx）。" +
            "支持格式：docx（#/##/### 标题、- 列表、| 表格、段落）；xlsx（## sheet名、| 表格行）。" +
            "写入路径必须在项目目录内。内容完整替换原文件。"
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
