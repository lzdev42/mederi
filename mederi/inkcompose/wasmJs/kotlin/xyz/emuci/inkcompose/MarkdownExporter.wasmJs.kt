package xyz.emuci.inkcompose

internal actual fun getBundledMongolianFontBase64(): String? = null

internal actual suspend fun exportMarkdownHtmlToPdfPlatform(
    html: String,
    outputPath: String,
    options: PdfExportOptions,
): Result<String> {
    return Result.failure(UnsupportedOperationException("PDF export is not supported on WasmJs yet."))
}
