package xyz.mederi.util

import kotlinx.browser.window

actual fun openUrl(url: String) {
    window.open(url, "_blank")
}

actual fun openFile(path: String) {
    // wasm 无法访问本地文件系统；浏览器端由 server 托管计划文件后走 openUrl
}
