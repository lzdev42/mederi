package xyz.mederi.util

/**
 * 文件「点击后怎么打开」的分档结果（四档）。
 *
 * - [INTERNAL_TEXT]：内部文本查看器（ARTIFACTS → MarkdownView）能直接渲染
 * - [INTERNAL_IMAGE]：内部图片查看器（ARTIFACTS → InkImage/Coil3）能直接渲染本地路径
 * - [EXTERNAL]：内部不适合看，交系统默认程序打开（`PlatformUtils.openFile`）
 * - [REVEAL_IN_FOLDER]：内部既不能看也没有可信的默认程序，退化为「打开所在目录」
 *   （`revealInFolder`）
 */
enum class FileOpenTarget { INTERNAL_TEXT, INTERNAL_IMAGE, EXTERNAL, REVEAL_IN_FOLDER }

/**
 * 按扩展名把任意文件路径分到 [FileOpenTarget] 四档之一——**对话中文件点击分级的唯一真理源**，
 * 被 `WorkspaceViewModel.openFile` 的路由消费。零 I/O，故全平台（jvm/android/ios/wasmJs）
 * 都能直接单测。
 *
 * 判据全部来自扩展名，不读盘、不查 MIME、不调 `PlatformUtils`：
 * - 文本类 = MarkdownView 能渲染的 String 子集（markdown / 纯文本 / 源码配置）；
 * - 图片类 = InkImage（Coil3）支持的本地路径格式；
 * - 其余（office/pdf/html/压缩/二进制/媒体）一律 EXTERNAL——注意 `html/htm` 归 EXTERNAL
 *   是明确口径（交给浏览器开），**不**因为"能当文本看"就归 INTERNAL_TEXT；
 * - 无扩展名（Makefile/Dockerfile）、未知扩展名、以及 `.gitignore` 这类隐藏文件
 *   （`substringAfterLast('.')` 会得到 `gitignore`）都落 [FileOpenTarget.REVEAL_IN_FOLDER]。
 */
fun classifyFilePath(path: String): FileOpenTarget {
    // 纯字符串运算：零 I/O。大小写不敏感（.KT / .MD / .PNG 与小写等价），忽略扩展名两端空白。
    val ext = path.substringAfterLast('.', "").lowercase().trim()
    if (ext.isEmpty()) return FileOpenTarget.REVEAL_IN_FOLDER
    return when (ext) {
        // ---- 内部文本查看器（MarkdownView 能渲染的 String 子集）----
        "md", "markdown", "txt", "log",
        "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx",
        "json", "xml", "yaml", "yml", "toml", "gradle", "sql",
        "sh", "bash", "zsh",
        "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "rb", "swift", "php", "lua",
        "css", "scss", "less",
        "properties", "ini", "conf", "cfg", "env",
        "dockerfile", "makefile" -> FileOpenTarget.INTERNAL_TEXT

        // ---- 内部图片查看器（InkImage / Coil3 支持的本地路径格式）----
        "png", "jpg", "jpeg", "webp", "bmp", "gif", "ico", "svg", "svgz" ->
            FileOpenTarget.INTERNAL_IMAGE

        // ---- 内部不适合看 → 系统默认程序（PlatformUtils.openFile）----
        // office 新式 / 旧式
        "docx", "xlsx", "pptx", "doc", "xls", "ppt",
        // 金山 WPS / Apple iWork
        "wps", "et", "dps", "pages", "numbers", "key",
        // PDF 与网页（html 归外部是明确口径：交给浏览器）
        "pdf", "html", "htm",
        // 压缩包
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
        // 二进制 / 可执行
        "exe", "bin", "dll", "so", "dylib", "class", "jar",
        // 音视频
        "mp4", "mov", "mkv", "avi", "mp3", "wav", "flac", "aac", "ogg" ->
            FileOpenTarget.EXTERNAL

        // 未知扩展名（含 .gitignore → "gitignore" 这类隐藏文件特例）
        else -> FileOpenTarget.REVEAL_IN_FOLDER
    }
}
