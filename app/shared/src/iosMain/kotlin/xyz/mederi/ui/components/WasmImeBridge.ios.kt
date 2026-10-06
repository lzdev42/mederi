package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.input.TextFieldValue

/**
 * ios actual：iOS 原生键盘正常服务 Compose 文本输入（CMP 内置 IME 链路），无需 DOM 桥接，no-op。
 * 注：wasmJs 版本因 Compose 网页端无法直接唤起 iOS 软键盘，才需要透明 textarea 覆盖技巧。
 */
@Composable
actual fun WasmImeBridge(
    textValue: TextFieldValue,
    enabled: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    rectPx: Rect?,
    density: Float,
) {
    // no-op
}