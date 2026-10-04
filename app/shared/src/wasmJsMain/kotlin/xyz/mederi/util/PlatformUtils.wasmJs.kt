package xyz.mederi.util

import kotlinx.browser.window

actual fun openUrl(url: String) {
    window.open(url, "_blank")
}

actual fun openFile(path: String): Boolean {
    // wasm 无法访问本地文件系统；浏览器端由 server 托管计划文件后走 openUrl
    return false
}

// 遥控端：文件不在本地，不实现；desktop jvm 是唯一实际消费方
actual fun defaultAppNameFor(path: String): String? = null
actual fun revealInFolder(path: String): Boolean = false
