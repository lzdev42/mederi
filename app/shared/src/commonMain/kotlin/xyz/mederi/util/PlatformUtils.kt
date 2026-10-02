package xyz.mederi.util

expect fun openUrl(url: String)

/** 用系统默认应用打开本地文件（如 .md 计划文件）。 */
expect fun openFile(path: String)

/**
 * 查询系统为 [path] 注册的默认打开程序的**人类可读名**（用于「是否用 XXX 打开？」对话框）。
 * 查不到/平台不支持时返回 null，调用方据此降级为泛称「系统默认应用」或「打开所在目录」。
 *
 * 平台能力差异（详见调研报告第二部分）：
 * - Linux：`xdg-mime` → `.desktop` 的 `Name=`，可直出人类可读名；
 * - Windows：`assoc`+`ftype` 取 exe 路径，再查友好名表；
 * - macOS：纯命令拿不到精确应用名，**返回 null**（不引 JNA），调用方走泛称。
 */
expect fun defaultAppNameFor(path: String): String?

/**
 * 在文件管理器里揭示（选中）[path] 所在文件。无注册可打开程序时改用此动作。
 * 成功返回 true；平台不支持 / 命令缺失 / 失败返回 false（调用方可再降级为打开父目录）。
 *
 * 平台实现：mac `open -R` / Windows `explorer /select,` / Linux DE select（dolphin --select）失败回落开父目录。
 */
expect fun revealInFolder(path: String): Boolean

/**
 * 格式化 Token / 上下文窗口规格（例如 1048576/1000000 -> "1M", 512000 -> "512K", 262144 -> "256K", 200000 -> "200K", 131072 -> "128K", 8192 -> "8K"）。
 */
fun formatContextWindow(tokens: Int?): String? {
    if (tokens == null || tokens <= 0) return null
    return when {
        tokens in 950_000..1_100_000 -> "1M"
        tokens in 1_900_000..2_150_000 -> "2M"
        tokens in 3_800_000..4_200_000 -> "4M"
        tokens >= 1_000_000 -> {
            val m = tokens / 1_000_000.0
            if (m % 1.0 == 0.0) "${m.toInt()}M" else "${((m * 10).toLong()) / 10.0}M"
        }
        tokens in 500_000..530_000 -> "512K"
        tokens in 250_000..270_000 -> "256K"
        tokens in 190_000..210_000 -> "200K"
        tokens in 125_000..135_000 -> "128K"
        tokens in 60_000..68_000 -> "64K"
        tokens in 30_000..35_000 -> "32K"
        tokens in 15_000..18_000 -> "16K"
        tokens in 7_800..8_500 -> "8K"
        tokens in 3_800..4_300 -> "4K"
        tokens >= 1_000 -> {
            val k = if (tokens % 1024 == 0) tokens / 1024 else tokens / 1000
            "${k}K"
        }
        else -> "$tokens"
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
