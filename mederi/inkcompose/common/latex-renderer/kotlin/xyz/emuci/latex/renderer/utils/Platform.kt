package xyz.emuci.latex.renderer.utils

/**
 * 平台类型枚举
 */
enum class PlatformType {
    ANDROID,
    IOS,
    JVM,
    JS,
    WASM
}

/**
 * 获取当前运行平台
 */
expect fun getCurrentPlatform(): PlatformType

/**
 * 检查是否为移动端平台（Android 或 iOS）
 */
fun isMobilePlatform(): Boolean {
    return when (getCurrentPlatform()) {
        PlatformType.ANDROID, PlatformType.IOS -> true
        else -> false
    }
}
