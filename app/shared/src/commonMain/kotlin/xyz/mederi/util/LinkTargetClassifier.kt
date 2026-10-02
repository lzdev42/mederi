package xyz.mederi.util

/**
 * 链接目标分档（MarkdownView onLinkClick 消费）：把任意 URL/路径分成「本地路径」与「外部链接」两档。
 *
 * 纯字符串判定、零 I/O（commonMain 禁用 java.net.URI），全平台可直接单测——
 * 与 [FileTypeClassifier]（文件点击分档）同属「输入分类」唯一真理源。
 */
object LinkTargetClassifier {

    /**
     * 提取本地路径；非本地链接（http/https/mailto…）返回 null：
     * - `file://` 前缀 → 剥前缀
     * - `file:` 前缀 → 剥前缀
     * - `/` 开头 → 绝对路径原样返回
     * - `X:` 盘符形式（Windows `C:\...`）→ 原样返回
     */
    fun localPathOrNull(url: String): String? = when {
        url.startsWith("file://") -> url.removePrefix("file://")
        url.startsWith("file:") -> url.removePrefix("file:")
        url.startsWith("/") -> url
        url.length > 2 && url[1] == ':' -> url // Windows 盘符路径 C:\...
        else -> null
    }
}
