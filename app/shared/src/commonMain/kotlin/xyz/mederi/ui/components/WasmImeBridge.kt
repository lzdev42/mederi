package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue

/**
 * wasmJs IME 桥接：DOM <textarea> 作为 wasmJs 上唯一的可见文本输入面（single-engine），
 * 触发 iOS 软键盘并回灌输入到 Compose；Compose TextField 的文字被 UI 层设透明、placeholder 设 null
 * （见 [xyz.mederi.isDomTextInput]）。
 *
 * - wasmJs actual：创建并维护一个可见 <textarea>（文字颜色 = [textColorHex]，placeholder 由
 *   [placeholderText]/[placeholderColorHex] 经 CSS 样式注入），按 [rectPx]（Compose px，除以
 *   [density] 得 CSS px）覆盖输入框，监听 input/composition 事件回灌 [onValueChange]，同时把 VM
 *   侧文本反同步回 textarea。硬约束：组合期（compositionstart..compositionend）绝不反写
 *   textarea.value/selection，input 事件在组合期不回灌（等 compositionend 一次性回灌）——
 *   中文输入不丢候选的关键。keydown 监听 Enter（无 Shift/Ctrl/Meta）触发 [onSend]（null = 不发送）。
 * - 光标：`caret-color` = [cursorColorHex]（主题强调色），随主题着色。
 *   不用 FocusRequester 给 Compose TextField 发 Compose 焦点——那会让 Compose 画布抢 DOM 焦点、
 *   顶掉 textarea 焦点导致键盘一弹即关（已验证的事故）。
 * - 其他平台（jvm/android/ios）no-op：desktop 原生键盘、Android/iOS 系统软键盘直接服务
 *   Compose 文本输入，无需 DOM 层桥接。
 */
@Composable
expect fun WasmImeBridge(
    textValue: TextFieldValue,
    enabled: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    cursorColorHex: String,
    textColorHex: String,           // 可见文字颜色 (textPrimary)
    placeholderText: String,        // placeholder 文字
    placeholderColorHex: String,    // placeholder 颜色 (textSecondary)
    onSend: (() -> Unit)?,          // Enter 发送回调；null = 不发送 (canSend=false 或 slashMenuOpen)
    rectPx: Rect?,
    density: Float,
)

/** Compose [Color] → CSS `#rrggbb`（不含 alpha，假定不透明色用于光标/文字）。 */
internal fun mederiColorToCssHex(color: Color): String {
    fun h(v: Float): String = (v * 255f).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#${h(color.red)}${h(color.green)}${h(color.blue)}"
}
