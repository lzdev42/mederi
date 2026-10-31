package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isCtrlPressed

internal actual fun PointerEvent.isSecondaryClick(): Boolean = buttons.isSecondaryPressed ||
    (isMacPlatform() && buttons.isPrimaryPressed && keyboardModifiers.isCtrlPressed)

// 浏览器 canvas 已拦截原生 contextmenu；macOS Control-click 必须走同一自定义菜单。
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun isMacPlatform(): Boolean = js("/Mac/.test(navigator.platform)")
