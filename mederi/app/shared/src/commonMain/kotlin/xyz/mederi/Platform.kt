package xyz.mederi

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/**
 * 是否为桌面平台（JVM）。
 * 用于键盘行为差异：桌面端 Enter=发送、Ctrl/Cmd+Enter=换行；非桌面端 Enter=换行。
 */
expect val isDesktopPlatform: Boolean
