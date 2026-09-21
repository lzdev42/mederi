package xyz.mederi.util

import xyz.emuci.inkcompose.HtmlExportOptions
import xyz.emuci.inkcompose.MarkdownExporter
import xyz.emuci.inkcompose.PdfExportOptions

/**
 * 文档导出状态。
 */
enum class ExportStatus {
    /** 导出成功（文件已写入并打开） */
    EXPORTED,

    /** 用户在保存文件对话框取消（非失败，UI 不展示错误） */
    CANCELLED,

    /**
     * 写入/转换失败且无异常详情（HTML 的 writeTextToFile 返回 false、
     * PDF 的 toPdf 返回失败且无异常消息）——UI 用格式专属兜底文案（如"HTML 导出失败"）。
     */
    WRITE_FAILED,

    /**
     * 捕获到异常（或 toPdf 返回失败但带异常消息）——[ExportResult.error] 为原始消息，
     * 为 null 时 UI 用通用导出失败文案兜底。
     */
    EXPORT_FAILED,
}

/**
 * 文档导出结果。
 *
 * @param status 导出状态（见 [ExportStatus]）
 * @param error 失败时的原始错误信息；成功/取消/无详情失败时为 null
 */
data class ExportResult(
    val status: ExportStatus,
    val error: String? = null,
) {
    /** 是否导出成功。 */
    val exported: Boolean get() = status == ExportStatus.EXPORTED

    /** 是否用户取消（非失败）。 */
    val cancelled: Boolean get() = status == ExportStatus.CANCELLED
}

/**
 * 文档导出器：把 Markdown 文本导出为 HTML / PDF 文件的全链路，UI 层只负责按钮与状态展示。
 *
 * 链路：pickSaveFile（平台保存对话框，expect/actual 胶水）→ MarkdownExporter 转换
 * → writeTextToFile → openFile。
 *
 * [saveDialogTitle] / [filterLabel] 为平台对话框的本地化文案，由 Composable 层
 * stringResource 解析后传入（本文件非 Composable，不能直接取资源）。
 */
object DocumentExporter {

    /**
     * 导出为自包含 HTML：转 HTML 后写入用户选择的文件，成功后用系统默认应用打开。
     *
     * 取消（pickSaveFile 返回 null）→ CANCELLED；
     * writeTextToFile 返回 false → WRITE_FAILED（error = null）；
     * 其他异常 → EXPORT_FAILED 且 error = 异常消息。
     */
    suspend fun exportDocumentToHtml(
        title: String,
        content: String,
        baseName: String,
        saveDialogTitle: String,
        filterLabel: String,
    ): ExportResult {
        val path = pickSaveFile(baseName, "html", saveDialogTitle, filterLabel)
            ?: return ExportResult(status = ExportStatus.CANCELLED)
        return try {
            val html = MarkdownExporter.toHtml(content, HtmlExportOptions(title = title))
            val ok = writeTextToFile(path, html)
            if (ok) {
                openFile(path)
                ExportResult(status = ExportStatus.EXPORTED)
            } else {
                ExportResult(status = ExportStatus.WRITE_FAILED)
            }
        } catch (e: Exception) {
            ExportResult(status = ExportStatus.EXPORT_FAILED, error = e.message)
        }
    }

    /**
     * 导出为 PDF：经 MarkdownExporter.toPdf 写入用户选择的文件，成功后用系统默认应用打开。
     *
     * 取消 → CANCELLED；
     * toPdf 返回失败且带异常消息 → EXPORT_FAILED（error = 异常消息）；
     * toPdf 返回失败但无异常消息 → WRITE_FAILED；
     * 其他异常 → EXPORT_FAILED 且 error = 异常消息。
     */
    suspend fun exportDocumentToPdf(
        title: String,
        content: String,
        baseName: String,
        saveDialogTitle: String,
        filterLabel: String,
    ): ExportResult {
        val path = pickSaveFile(baseName, "pdf", saveDialogTitle, filterLabel)
            ?: return ExportResult(status = ExportStatus.CANCELLED)
        return try {
            val result = MarkdownExporter.toPdf(content, path, PdfExportOptions(title = title))
            val failure = result.exceptionOrNull()
            when {
                result.isSuccess -> {
                    openFile(path)
                    ExportResult(status = ExportStatus.EXPORTED)
                }
                failure?.message != null -> ExportResult(status = ExportStatus.EXPORT_FAILED, error = failure.message)
                else -> ExportResult(status = ExportStatus.WRITE_FAILED)
            }
        } catch (e: Exception) {
            ExportResult(status = ExportStatus.EXPORT_FAILED, error = e.message)
        }
    }
}