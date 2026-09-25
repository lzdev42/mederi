package xyz.mederi

/**
 * JVM 平台（desktop macOS/Windows/Linux）：读 JVM 系统属性。
 * 唯一获取入口，供 AppInfo.userAgent 使用，禁止在其他位置各自读取 os.name / os.version / os.arch。
 */
internal actual fun platformInfo(): PlatformInfo {
    val rawOs = System.getProperty("os.name") ?: "unknown"
    val osVersion = System.getProperty("os.version")?.takeIf { it.isNotBlank() } ?: "unknown"
    val rawArch = System.getProperty("os.arch") ?: "unknown"
    return PlatformInfo(
        osName = normalizeOsName(rawOs),
        osVersion = osVersion,
        arch = normalizeArch(rawArch),
    )
}
