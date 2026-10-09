package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.input.TextFieldValue

/**
 * JVM actual：desktop 宿主原生键盘直接服务 Compose 文本输入（TextField 自身可输入），无需桥接，no-op。
 */
@Composable
actual fun WasmImeBridge(
    textValue: TextFieldValue,
    enabled: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    cursorColorHex: String,
    rectPx: Rect?,
    density: Float,
) {
    // no-op
}
