package xyz.mederi.util

expect fun openUrl(url: String)

/** 用系统默认应用打开本地文件（如 .md 计划文件）。 */
expect fun openFile(path: String)

/**
 * 格式化 Token / 上下文窗口数字（例如 1024000 -> "102.4万"，262144 -> "26.2万"，8192 -> "8K"）。
 */
fun formatContextWindow(tokens: Int?): String? {
    if (tokens == null || tokens <= 0) return null
    return if (tokens >= 10_000) {
        val count = tokens / 10_000.0
        val formatted = if (count % 1.0 == 0.0) {
            "${count.toInt()}"
        } else {
            val rounded = ((count * 10).toLong()) / 10.0
            "$rounded"
        }
        "${formatted}万"
    } else {
        "${tokens / 1000}K"
    }
}

/**
 * 格式化字节大小为易读字符串（如 1024 -> "1.0 KB", 1073741824 -> "1.0 GB"）。
 * null 时返回 "—"。
 */
fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "—"
    if (bytes < 0) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> {
            val v = ((bytes / gb * 10).toLong()) / 10.0
            "$v GB"
        }
        bytes >= mb -> {
            val v = ((bytes / mb * 10).toLong()) / 10.0
            "$v MB"
        }
        bytes >= kb -> {
            val v = ((bytes / kb * 10).toLong()) / 10.0
            "$v KB"
        }
        else -> "$bytes B"
    }
}

/**
 * 格式化 CPU 使用率（0.0~1.0 比例转换为百分比，如 0.185 -> "18.5%"）。
 * null 时返回 "—"。
 */
fun formatCpuUsage(usage: Double?): String {
    if (usage == null) return "—"
    val percent = (usage * 100.0).coerceAtLeast(0.0)
    val rounded = ((percent * 10).toLong()) / 10.0
    return "$rounded%"
}
