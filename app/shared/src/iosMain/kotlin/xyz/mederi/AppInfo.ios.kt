@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package xyz.mederi

import kotlin.native.Platform
import platform.UIKit.UIDevice

/**
 * iOS 平台：UIDevice 提供系统版本，kotlin.native.Platform 提供 CPU 架构。
 * 唯一获取入口，供 AppInfo.userAgent 使用，禁止在其他位置各自读取。
 */
internal actual fun platformInfo(): PlatformInfo {
    val osName = "iOS"
    val osVersion = UIDevice.currentDevice.systemVersion?.takeIf { it.isNotBlank() } ?: "unknown"
    return PlatformInfo(
        osName = osName,
        osVersion = osVersion,
        arch = normalizeArch(iosCpuArch()),
    )
}

/** kotlin.native.Platform.cpuArchitecture → 字符串架构名（统一进 normalizeArch 标准化）。 */
private fun iosCpuArch(): String = when (val name = Platform.cpuArchitecture.name) {
    "ARM64" -> "arm64"
    "X86_64" -> "x86_64"
    "X86" -> "x86"
    else -> name
}
