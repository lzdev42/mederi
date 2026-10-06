package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.input.TextFieldValue

/**
 * wasmJs IME 桥接：用透明 <textarea> 覆盖 TextField，触发 iOS 软键盘并回灌输入到 Compose。
 *
 * - wasmJs actual：创建并维护一个透明 <textarea>，按 [rectPx]（Compose px，除以 [density] 得 CSS px）
 *   覆盖输入框，监听 input/composition 事件回灌 [onValueChange]，同时把 VM 侧文本反同步回 textarea。
 *   硬约束：组合期（compositionstart..compositionend）绝不反写 textarea.value/selection，
 *   input 事件在组合期不回灌（等 compositionend 一次性回灌）——中文输入不丢候选的关键。
 * - [onFocusChange]：textarea 获得/失去 DOM 焦点时回调（true/false）。调用方据此用 FocusRequester
 *   给 Compose TextField 发 Compose 焦点，使 Canvas 渲染自己的光标（textarea 自身光标被设为透明）；
 *   否则两层光标都不可见（DOM 焦点在 textarea → Compose TextField 未 focused → Canvas 不画光标）。
 * - 其他平台（jvm/android/ios）no-op：desktop 原生键盘、Android/iOS 系统软键盘直接服务
 *   Compose 文本输入，无需 DOM 层桥接。
 */
@Composable
expect fun WasmImeBridge(
    textValue: TextFieldValue,
    enabled: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    rectPx: Rect?,
    density: Float,
)