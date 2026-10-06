package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.browser.document
import org.w3c.dom.HTMLTextAreaElement

/**
 * wasmJs actual：透明 <textarea> 覆盖 Web 端 Compose TextField，触发 iOS 软键盘并回灌输入。
 *
 * 原理：Web 端（含 iOS Safari）Compose 的文本输入无法直接唤起系统软键盘，因此把一个透明
 * <textarea> 固定在 TextField 上方的窗口坐标处，让系统把输入焦点/软键盘给到它；它的
 * input/composition 事件回灌成 [TextFieldValue]（经 [onValueChange] 进 VM），VM 侧的文本
 * 变化（如斜杠命令自动补全、粘贴）再反同步回 textarea。
 *
 * 硬约束（CJK 输入不丢候选的关键，勿改）：
 * 1. 组合期（compositionstart..compositionend）绝不反写 textarea.value/selection；
 * 2. input 事件在组合期不回灌，等 compositionend 一次性回灌。
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
    // 仅创建一次 DOM 元素（组合刷新不重建）
    val textarea = remember { document.createElement("textarea") as HTMLTextAreaElement }
    // 事件回调闭包捕获最新回调，避免 DisposableEffect 只安装一次导致 stale closure
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    val latestOnFocusChange = rememberUpdatedState(onFocusChange)
    // 组合期标志（JS 事件回调里读写，用快照状态即可，闭包捕获引用）
    val isComposing = remember { mutableStateOf(false) }

    // 初始化样式 + 挂到 body；离开组合时移除
    DisposableEffect(textarea) {
        val style = textarea.style
        style.position = "fixed"
        style.opacity = "0"
        style.color = "transparent"
        style.setProperty("caret-color", "transparent")
        style.fontSize = "16px" // 防 iOS 自动缩放
        style.border = "0"
        style.padding = "0"
        style.margin = "0"
        style.resize = "none"
        style.overflowX = "hidden"
        style.overflowY = "hidden"
        style.outline = "none"
        style.background = "transparent"
        style.zIndex = "999999"
        style.whiteSpace = "pre-wrap"
        style.wordWrap = "break-word"
        style.display = "none" // 初始隐藏，由 enabled/rectPx 控制显隐
        // 关闭浏览器原生增强，避免与 Compose 侧行为打架
        textarea.setAttribute("autocapitalize", "off")
        textarea.setAttribute("autocomplete", "off")
        textarea.setAttribute("autocorrect", "off")
        textarea.setAttribute("spellcheck", "false")
        document.body?.appendChild(textarea)

        // 读 textarea 当前 value/选区 → 回灌 Compose（组合期不回灌，由调用方判断）
        fun flush() {
            val value = textarea.value
            val start = textarea.selectionStart?.toInt() ?: 0
            val end = textarea.selectionEnd?.toInt() ?: 0
            latestOnValueChange.value(TextFieldValue(value, TextRange(start, end)))
        }

        // textarea 获得/失去 DOM 焦点 → 通知调用方给 Compose TextField 发/清 Compose 焦点，
        // 使 Canvas 渲染自己的光标（textarea 自身 caret-color 被设为 transparent）。
        textarea.addEventListener("focus") { latestOnFocusChange.value(true) }
        textarea.addEventListener("blur") { latestOnFocusChange.value(false) }

        textarea.addEventListener("compositionstart") { isComposing.value = true }
        textarea.addEventListener("compositionend") {
            isComposing.value = false
            flush()
        }
        // 组合期 input 事件不回灌（避免打断 IME 候选），等 compositionend 一次性回灌
        textarea.addEventListener("input") {
            if (!isComposing.value) flush()
        }
        // selectionchange 事件（选区单独变化时回灌）：v1 不需要，随 composition/input 一并回传即可

        onDispose {
            textarea.remove()
        }
    }

    // 定位与显隐：rectPx 是 Compose px（= CSS px * density），除 density 得 CSS px。
    // 假设全屏 canvas（webApp/index.html body 无偏移），故 positionInWindow ≈ 视口坐标。
    LaunchedEffect(rectPx, density, enabled) {
        if (!enabled || rectPx == null) {
            textarea.style.display = "none"
        } else {
            val left = rectPx.left / density
            val top = rectPx.top / density
            val width = rectPx.width / density
            val height = rectPx.height / density
            textarea.style.left = "${left}px"
            textarea.style.top = "${top}px"
            textarea.style.width = "${width}px"
            textarea.style.height = "${height}px"
            textarea.style.display = "block"
        }
    }

    // VM → textarea 反向同步（组合期绝不反写 value/selection）。
    // 设 textarea.value 会把光标重置到末尾，必须紧随其后设置 selectionStart/End（同一块内）。
    LaunchedEffect(textValue.text, textValue.selection) {
        if (!isComposing.value) {
            if (textarea.value != textValue.text) {
                textarea.value = textValue.text
            }
            val s = textValue.selection.start
            val e = textValue.selection.end
            if (textarea.selectionStart != s || textarea.selectionEnd != e) {
                textarea.selectionStart = s
                textarea.selectionEnd = e
            }
        }
    }
}