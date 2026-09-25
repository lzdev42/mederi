package xyz.mederi

import kotlinx.browser.window

/**
 * Web (wasmJs) 平台：浏览器没有系统 API，从 navigator.userAgent 解析（标准 UA 注释粒度）。
 * 唯一获取入口，供 AppInfo.userAgent 使用，禁止在其他位置各自解析。
 */
internal actual fun platformInfo(): PlatformInfo {
    val ua = window.navigator.userAgent

    val osName = when {
        ua.contains("Android") -> "Android"
        ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod") -> "iOS"
        ua.contains("Windows") -> "Windows NT"
        ua.contains("Mac OS X") || ua.contains("Macintosh") -> "Mac OS X"
        ua.contains("Linux") -> "Linux"
        else -> "unknown"
    }
    val osVersion = when (osName) {
        "Android" -> Regex("Android (\\d+(?:\\.\\d+)*)").find(ua)?.groupValues?.get(1) ?: "unknown"
        "iOS" -> Regex("(?:iPhone )?OS (\\d+)_(\\d+)(?:_(\\d+))?")
            .find(ua)?.groupValues?.let {
                buildString {
                    append(it[1]).append('.').append(it[2])
                    if (it.size > 3 && it[3].isNotBlank()) append('.').append(it[3])
                }
            } ?: "unknown"
        "Windows NT" -> Regex("Windows NT (\\d+\\.\\d+)").find(ua)?.groupValues?.get(1) ?: "unknown"
        "Mac OS X" -> Regex("Mac OS X (\\d+)[._](\\d+)(?:[._](\\d+))?")
            .find(ua)?.groupValues?.let {
                buildString {
                    append(it[1]).append('.').append(it[2])
                    if (it.size > 3 && it[3].isNotBlank()) append('.').append(it[3])
                }
            } ?: "unknown"
        else -> "unknown"
    }
    val arch = Regex("(aarch64|arm64|x86_64|x64|i686|i386|x86)").find(ua)?.groupValues?.get(1) ?: "unknown"

    return PlatformInfo(
        osName = osName,
        osVersion = osVersion,
        arch = normalizeArch(arch),
    )
}
