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

private val KNOWN_IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "webp", "bmp", "gif", "ico", "svg", "svgz"
)

private val KNOWN_EXTERNAL_EXTENSIONS = setOf(
    // office 新式 / 旧式 / WPS / iWork
    "docx", "xlsx", "pptx", "doc", "xls", "ppt",
    "wps", "et", "dps", "pages", "numbers", "key",
    // PDF 与网页（html 归外部交给系统默认浏览器）
    "pdf", "html", "htm",
    // 压缩包
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz",
    // 二进制 / 可执行
    "exe", "bin", "dll", "so", "dylib", "class", "jar", "iso", "dmg", "pkg", "apk", "ipa",
    // 音视频
    "mp4", "mov", "mkv", "avi", "mp3", "wav", "flac", "aac", "ogg", "webm"
)

/**
 * 按扩展名把任意文件路径分到 [FileOpenTarget] 档位——**对话中文件点击分级的唯一真理源**，
 * 被 `WorkspaceViewModel.openFile` 的路由消费。零 I/O，故全平台（jvm/android/ios/wasmJs）
 * 都能直接单测。
 *
 * 判据全部来自扩展名，不读盘、不查 MIME、不调 `PlatformUtils`：
 * - 图片类 = InkImage（Coil3）支持的本地路径格式，归 [FileOpenTarget.INTERNAL_IMAGE]；
 * - 明确外部格式（Office/PDF/HTML/压缩/二进制/音视频）归 [FileOpenTarget.EXTERNAL]；
 * - 其余一切（无论常见源码、配置、无后缀如 Makefile/Dockerfile 还是未知扩展名）一律作为
 *   [FileOpenTarget.INTERNAL_TEXT] 候选——由消费端尝试读取并检测非 NUL 字节，
 *   是合法文本则在内置查看器直接打开，遇二进制或读取异常再降级外部处理。
 */
fun classifyFilePath(path: String): FileOpenTarget {
    val ext = path.substringAfterLast('.', "").lowercase().trim()
    if (ext in KNOWN_IMAGE_EXTENSIONS) return FileOpenTarget.INTERNAL_IMAGE
    if (ext in KNOWN_EXTERNAL_EXTENSIONS) return FileOpenTarget.EXTERNAL
    return FileOpenTarget.INTERNAL_TEXT
}

/**
 * 按扩展名返回代码围栏（```lang）用的语言标签，供 `openFile` 把纯文本文件
 * 包进 MarkdownView 的代码围栏时使用。
 *
 * - 返回标签与 inkcompose `LanguageRegistry` 注册的语言一一对应——有词法器的
 *   后缀才能拿到非 null 标签，确保围栏内能正确高亮；
 * - 返回 `null` 表示该后缀没有对应词法器，调用方自行决定：用裸围栏（``` 不带
 *   语言）还是直接按纯文本渲染（不包围栏）。
 *
 * 与 [classifyFilePath] 同口径取后缀：`path.substringAfterLast('.', "").lowercase().trim()`，
 * 大小写不敏感、忽略两端空白；空串（无扩展名）返回 null。
 *
 * @param path 任意文件路径（纯字符串运算，零 I/O，不做任何系统调用）。
 * @return 围栏语言标签；无词法器对应时返回 null。
 */
fun codeFenceLanguageFor(path: String): String? {
    val fileName = path.substringAfterLast('/').lowercase().trim()
    if (fileName == "makefile") return "makefile"
    if (fileName == "dockerfile") return "dockerfile"
    if (fileName == "cmakelists.txt") return "cmake"

    val ext = path.substringAfterLast('.', "").lowercase().trim()
    if (ext.isEmpty()) return null
    return when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "json" -> "json"
        "xml" -> "xml"
        "yaml", "yml" -> "yaml"
        "toml" -> "toml"
        "sql", "sq" -> "sql"
        "proto" -> "protobuf"
        "sh", "bash", "zsh", "env" -> "bash"
        "c", "h" -> "c"
        "cpp", "hpp", "cc" -> "cpp"
        "go" -> "go"
        "rs" -> "rust"
        "rb" -> "ruby"
        "swift" -> "swift"
        "php" -> "php"
        "lua" -> "lua"
        "css", "scss", "less" -> "css"
        "html", "vue", "svelte", "astro" -> "html"
        "dockerfile" -> "dockerfile"
        "dart" -> "dart"
        "scala", "sc" -> "scala"
        "hs" -> "haskell"
        "ex", "exs" -> "elixir"
        "r" -> "r"
        "diff", "patch" -> "diff"
        "properties", "ini", "conf", "cfg" -> "ini"
        else -> null
    }
}
