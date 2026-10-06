package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.input.TextFieldValue

/**
 * android actual：Android 系统软键盘（InputMethodService 链路）直接服务 Compose 文本输入，无需桥接，no-op。
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