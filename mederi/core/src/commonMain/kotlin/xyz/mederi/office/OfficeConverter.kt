package xyz.mederi.office

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.apache.poi.xwpf.usermodel.XWPFTableCell
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.zwobble.mammoth.DocumentConverter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Office 文档转换器（JVM-only，无 KMP 替代品）。
 *
 * 三条转换链路：
 * 1. **预览（→HTML）**：docx 用 mammoth（质量好），xlsx/pptx 用 POI 手拼 HTML。
 * 2. **读（→Markdown）**：POI 提取文本结构 → markdown（AI 可读可改）。
 * 3. **写（Markdown→Office）**：从 markdown 生成新 docx/xlsx（基础样式）。
 *
 * 格式覆盖：
 * - docx：段落/标题/列表/表格 → markdown；写回反解析。
 * - xlsx：每个 sheet → markdown table；写回从 table 生成 sheet。
 * - pptx：每页 → `## Slide N` + bullet points（只读，不支持写回）。
 */
object OfficeConverter {

    // ==================== 预览：→ HTML ====================

    /** 根据扩展名分派到对应转换器，返回完整 HTML 文档字符串。 */
    fun toHtml(path: String): String {
        val ext = extensionOf(path)
        val body = when (ext) {
            "docx" -> docxToHtml(path)
            "xlsx" -> xlsxToHtml(path)
            "pptx" -> pptxToHtml(path)
            else -> throw IllegalArgumentException("Unsupported office format: .$ext (only .docx/.xlsx/.pptx)")
        }
        return wrapHtml(body, title = File(path).nameWithoutExtension)
    }

    /** docx → HTML via mammoth（保留标题/段落/列表/表格/图片引用）。 */
    fun docxToHtml(path: String): String {
        val converter = DocumentConverter()
        val result = converter.convertToHtml(File(path))
        return result.value
    }

    /** xlsx → HTML table（每 sheet 一个 `<table>`，带 `<h2>` sheet 名）。 */
    fun xlsxToHtml(path: String): String {
        FileInputStream(path).use { fis ->
            val wb = XSSFWorkbook(fis)
            val sb = StringBuilder()
            for (si in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(si)
                sb.append("<h2>").append(escapeHtml(sheet.sheetName)).append("</h2>\n")
                sb.append("<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:14px;\">\n")
                val rowIter = sheet.rowIterator()
                while (rowIter.hasNext()) {
                    val row = rowIter.next()
                    sb.append("<tr>")
                    val cellIter = row.cellIterator()
                    while (cellIter.hasNext()) {
                        val text = getCellText(cellIter.next())
                        sb.append("<td>").append(escapeHtml(text)).append("</td>")
                    }
                    sb.append("</tr>\n")
                }
                sb.append("</table>\n")
            }
            wb.close()
            return sb.toString()
        }
    }

    /** pptx → HTML（每 slide 一个 `<section>`，文字段落）。 */
    fun pptxToHtml(path: String): String {
        FileInputStream(path).use { fis ->
            val ppt = XMLSlideShow(fis)
            val sb = StringBuilder()
            for ((idx, slide) in ppt.slides.withIndex()) {
                sb.append("<section style=\"margin-bottom:2em;page-break-after:always;\">\n")
                sb.append("<h2>Slide ").append(idx + 1).append("</h2>\n")
                for (shape in slide.shapes) {
                    if (shape is XSLFTextShape) {
                        for (para in shape.textParagraphs) {
                            val text = para.text?.trim()
                            if (!text.isNullOrEmpty()) {
                                sb.append("<p>").append(escapeHtml(text)).append("</p>\n")
                            }
                        }
                    }
                }
                sb.append("</section>\n")
            }
            ppt.close()
            return sb.toString()
        }
    }

    // ==================== 读：→ Markdown（给 AI） ====================

    /** 根据扩展名分派，返回 markdown 文本。 */
    fun toMarkdown(path: String): String {
        val ext = extensionOf(path)
        return when (ext) {
            "docx" -> docxToMarkdown(path)
            "xlsx" -> xlsxToMarkdown(path)
            "pptx" -> pptxToMarkdown(path)
            else -> throw IllegalArgumentException("Unsupported office format: .$ext")
        }
    }

    /** docx → markdown（标题/段落/表格）。 */
    fun docxToMarkdown(path: String): String {
        FileInputStream(path).use { fis ->
            val doc = XWPFDocument(fis)
            val sb = StringBuilder()
            for (para in doc.paragraphs) {
                val text = para.text?.trim() ?: ""
                if (text.isEmpty()) {
                    sb.append("\n")
                    continue
                }
                val style = para.style ?: ""
                when {
                    style.contains("Heading1", true) || style.contains("标题 1", true) ->
                        sb.append("# ").append(text).append("\n\n")
                    style.contains("Heading2", true) || style.contains("标题 2", true) ->
                        sb.append("## ").append(text).append("\n\n")
                    style.contains("Heading3", true) || style.contains("标题 3", true) ->
                        sb.append("### ").append(text).append("\n\n")
                    style.contains("Heading", true) || style.contains("标题", true) ->
                        sb.append("#### ").append(text).append("\n\n")
                    else -> sb.append(text).append("\n\n")
                }
            }
            for (table in doc.tables) {
                appendTableMarkdown(sb, table)
            }
            doc.close()
            return sb.toString()
        }
    }

    /** xlsx → markdown（每 sheet 一个 markdown table）。 */
    fun xlsxToMarkdown(path: String): String {
        FileInputStream(path).use { fis ->
            val wb = XSSFWorkbook(fis)
            val sb = StringBuilder()
            for (si in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(si)
                sb.append("## ").append(sheet.sheetName).append("\n\n")
                val rows = sheet.rowIterator().asSequence().toList()
                if (rows.isEmpty()) {
                    sb.append("(empty sheet)\n\n")
                    continue
                }
                val maxCol = rows.maxOf { it.lastCellNum.toInt() }
                if (maxCol == 0) continue
                // 表头
                sb.append("|")
                for (c in 0 until maxCol) {
                    sb.append(" ").append(getCellText(rows[0], c)).append(" |")
                }
                sb.append("\n|")
                for (c in 0 until maxCol) sb.append("---|")
                sb.append("\n")
                // 数据行
                for (ri in 1 until rows.size) {
                    sb.append("|")
                    for (c in 0 until maxCol) {
                        sb.append(" ").append(getCellText(rows[ri], c)).append(" |")
                    }
                    sb.append("\n")
                }
                sb.append("\n")
            }
            wb.close()
            return sb.toString()
        }
    }

    /** pptx → markdown（每 slide 标题 + 文字段落）。 */
    fun pptxToMarkdown(path: String): String {
        FileInputStream(path).use { fis ->
            val ppt = XMLSlideShow(fis)
            val sb = StringBuilder()
            for ((idx, slide) in ppt.slides.withIndex()) {
                sb.append("## Slide ").append(idx + 1).append("\n\n")
                for (shape in slide.shapes) {
                    if (shape is XSLFTextShape) {
                        for (para in shape.textParagraphs) {
                            val text = para.text?.trim()
                            if (!text.isNullOrEmpty()) {
                                sb.append("- ").append(text).append("\n")
                            }
                        }
                    }
                }
                sb.append("\n")
            }
            ppt.close()
            return sb.toString()
        }
    }

    // ==================== 写：Markdown → Office ====================

    /** 从 markdown 创建/覆盖 docx。 */
    fun writeDocx(path: String, markdown: String) {
        FileOutputStream(path).use { fos ->
            val doc = XWPFDocument()
            val lines = markdown.split("\n")
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                when {
                    line.startsWith("# ") -> {
                        doc.createParagraph().apply {
                            style = "Heading1"
                            createRun().setText(line.removePrefix("# ").trim())
                        }
                    }
                    line.startsWith("## ") -> {
                        doc.createParagraph().apply {
                            style = "Heading2"
                            createRun().setText(line.removePrefix("## ").trim())
                        }
                    }
                    line.startsWith("### ") -> {
                        doc.createParagraph().apply {
                            style = "Heading3"
                            createRun().setText(line.removePrefix("### ").trim())
                        }
                    }
                    line.startsWith("#### ") -> {
                        doc.createParagraph().apply {
                            style = "Heading4"
                            createRun().setText(line.removePrefix("#### ").trim())
                        }
                    }
                    line.startsWith("- ") || line.startsWith("* ") -> {
                        doc.createParagraph().apply {
                            createRun().setText(line.drop(2).trim())
                        }
                    }
                    line.startsWith("|") && line.contains("---") -> {
                        // markdown table 分隔行：跳过
                    }
                    line.startsWith("|") -> {
                        // markdown table：收集连续 | 行（跳过分隔行）
                        val tableLines = mutableListOf<String>()
                        while (i < lines.size && lines[i].startsWith("|")) {
                            if (!lines[i].contains("---")) {
                                tableLines.add(lines[i])
                            }
                            i++
                        }
                        i--
                        if (tableLines.isNotEmpty()) {
                            val cols = tableLines[0].split("|").filter { it.isNotBlank() }.size
                            val table = doc.createTable(tableLines.size, cols)
                            for (ri in tableLines.indices) {
                                val cells = tableLines[ri].split("|").filter { it.isNotBlank() }
                                for (ci in cells.indices) {
                                    table.getRow(ri).getCell(ci).setText(cells[ci].trim())
                                }
                            }
                        }
                    }
                    line.isBlank() -> {
                        // 空行跳过
                    }
                    else -> {
                        doc.createParagraph().apply {
                            createRun().setText(line.trim())
                        }
                    }
                }
                i++
            }
            doc.write(fos)
            doc.close()
        }
    }

    /** 从 markdown 创建/覆盖 xlsx（从 markdown table 生成 sheet）。 */
    fun writeXlsx(path: String, markdown: String) {
        FileOutputStream(path).use { fos ->
            val wb = XSSFWorkbook()
            val lines = markdown.split("\n")
            var currentSheet: org.apache.poi.xssf.usermodel.XSSFSheet? = null
            var sheetIdx = 0
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("## ")) {
                    val name = line.removePrefix("## ").trim().take(31)
                    currentSheet = wb.createSheet(if (name.isBlank()) "Sheet${sheetIdx + 1}" else name)
                    sheetIdx++
                    i++
                    continue
                }
                if (line.startsWith("|") && !line.contains("---")) {
                    val sheet = currentSheet ?: run {
                        currentSheet = wb.createSheet("Sheet1")
                        sheetIdx++
                        currentSheet!!
                    }
                    val cells = line.split("|").filter { it.isNotBlank() }.map { it.trim() }
                    val nextRow = sheet.lastRowNum + 1
                    val row = sheet.createRow(nextRow)
                    for (ci in cells.indices) {
                        row.createCell(ci).setCellValue(cells[ci])
                    }
                }
                i++
            }
            if (wb.numberOfSheets == 0) {
                wb.createSheet("Sheet1")
            }
            wb.write(fos)
            wb.close()
        }
    }

    // ==================== 工具方法 ====================

    fun isSupported(path: String): Boolean {
        val ext = extensionOf(path)
        return ext == "docx" || ext == "xlsx" || ext == "pptx"
    }

    private fun extensionOf(path: String): String {
        val dot = path.lastIndexOf('.')
        return if (dot < 0) "" else path.substring(dot + 1).lowercase()
    }

    private fun wrapHtml(body: String, title: String): String {
        return buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            append("<title>").append(escapeHtml(title)).append("</title>\n")
            append("<style>\n")
            append("body { font-family: -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif; ")
            append("margin: 24px; color: #222; line-height: 1.6; }\n")
            append("h1 { font-size: 1.8em; }\nh2 { font-size: 1.4em; margin-top: 1.5em; }\n")
            append("h3 { font-size: 1.2em; }\n")
            append("table { border-collapse: collapse; width: auto; margin: 0.5em 0; }\n")
            append("td, th { border: 1px solid #ccc; padding: 4px 8px; }\n")
            append("th { background: #f0f0f0; }\n")
            append("img { max-width: 100%; height: auto; }\n")
            append("section { margin-bottom: 2em; }\n")
            append("</style>\n</head>\n<body>\n")
            append(body)
            append("\n</body>\n</html>")
        }
    }

    private fun appendTableMarkdown(sb: StringBuilder, table: XWPFTable) {
        val rows = table.rows
        if (rows.isEmpty()) return
        val cols = rows[0].tableCells.size
        // 表头
        sb.append("|")
        for (c in 0 until cols) {
            sb.append(" ").append(getCellText(rows[0], c)).append(" |")
        }
        sb.append("\n|")
        for (c in 0 until cols) sb.append("---|")
        sb.append("\n")
        // 数据行
        for (ri in 1 until rows.size) {
            sb.append("|")
            for (c in 0 until cols) {
                sb.append(" ").append(getCellText(rows[ri], c)).append(" |")
            }
            sb.append("\n")
        }
        sb.append("\n")
    }

    private fun getCellText(row: org.apache.poi.xwpf.usermodel.XWPFTableRow, col: Int): String {
        val cells = row.tableCells
        if (col >= cells.size) return ""
        return cells[col].text?.trim() ?: ""
    }

    private fun getCellText(cell: Cell): String {
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue?.trim() ?: ""
            CellType.NUMERIC -> {
                val v = cell.numericCellValue
                if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> cell.cellFormula ?: ""
            else -> ""
        }
    }

    private fun getCellText(row: Row, col: Int): String {
        val cell = row.getCell(col) ?: return ""
        return getCellText(cell)
    }

    private fun escapeHtml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
