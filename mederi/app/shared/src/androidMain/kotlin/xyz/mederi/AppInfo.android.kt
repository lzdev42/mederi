package xyz.mederi

/**
 * Android 平台：从 Build 读取系统版本与 ABI。
 * 唯一获取入口，供 AppInfo.userAgent 使用，禁止在其他位置各自读取 Build.VERSION / SUPPORTED_ABIS。
 */
internal actual fun platformInfo(): PlatformInfo {
    val osName = "Android"
    val osVersion = android.os.Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "unknown"
    val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
    return PlatformInfo(
        osName = osName,
        osVersion = osVersion,
        arch = normalizeArch(abi),
    )
}
