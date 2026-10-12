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
import org.w3c.dom.events.KeyboardEvent

/**
 * wasmJs actual：DOM <textarea> 作为 Web 端唯一可见文本输入面（single-engine），触发 iOS 软键盘并回灌输入。
 *
 * 原理：Web 端（含 iOS Safari）Compose 的文本输入无法直接唤起系统软键盘，因此把 <textarea> 固定在
 * TextField 上方的窗口坐标处，让系统把输入焦点/软键盘给到它；Compose TextField 文字设透明、
 * placeholder 设 null（见 [xyz.mederi.isDomTextInput]），故 textarea 的文字/placeholder 就是用户
 * 看到的文字/占位。它的 input/composition 事件回灌成 [TextFieldValue]（经 [onValueChange] 进 VM），
 * VM 侧的文本变化（如斜杠命令自动补全、粘贴）再反同步回 textarea。keydown 纯 Enter（无 Shift/Ctrl/Meta）
 * 触发 [onSend]（null = 不发送）；Shift/Ctrl/Cmd+Enter 保留默认换行。
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
    cursorColorHex: String,
    textColorHex: String,
    placeholderText: String,
    placeholderColorHex: String,
    onSend: (() -> Unit)?,
    rectPx: Rect?,
    density: Float,
) {
    // 仅创建一次 DOM 元素（组合刷新不重建）
    val textarea = remember { document.createElement("textarea") as HTMLTextAreaElement }
    // 事件回调闭包捕获最新回调，避免 DisposableEffect 只安装一次导致 stale closure
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    val latestOnSend = rememberUpdatedState(onSend)
    // 组合期标志（JS 事件回调里读写，用快照状态即可，闭包捕获引用）
    val isComposing = remember { mutableStateOf(false) }

    // 初始化样式 + 挂到 body；离开组合时移除
    DisposableEffect(textarea, cursorColorHex, textColorHex, placeholderColorHex, placeholderText) {
        val style = textarea.style
        style.position = "fixed"
        // 单引擎渲染：textarea 是唯一可见文字面（Canvas 文字/斜杠高亮已透明/隐藏），
        // 文字颜色直接 = 主题 textPrimary；caret-color 单独设主题色 → textarea 自身光标可见
        // 不用 opacity:0（那会连光标一起隐藏）。不用 FocusRequester 给 Compose 发焦点（会抢 DOM 焦点顶掉键盘）。
        style.color = textColorHex
        style.setProperty("caret-color", cursorColorHex)
        style.background = "transparent"
        style.fontSize = "16px" // 防 iOS 自动缩放（<16px 会触发 focus zoom，破坏覆盖层定位）
        style.fontFamily = "sans-serif" // 尽量贴近 Canvas 默认无衬线，减少光标横向错位
        style.lineHeight = "20px" // 对齐 Canvas textStyle lineHeight（20.sp = 20 CSS px），避免光标/文字错位
        style.border = "0"
        style.padding = "0"
        style.margin = "0"
        style.resize = "none"
        style.overflowX = "hidden"
        style.overflowY = "hidden"
        style.outline = "none"
        style.background = "transparent"
        style.zIndex = "2"
        style.whiteSpace = "pre-wrap"
        style.wordWrap = "break-word"
        style.display = "none" // 初始隐藏，由 enabled/rectPx 控制显隐
        // 关闭浏览器原生增强，避免与 Compose 侧行为打架
        textarea.setAttribute("autocapitalize", "off")
        textarea.setAttribute("autocomplete", "off")
        textarea.setAttribute("autocorrect", "off")
        textarea.setAttribute("spellcheck", "false")
        // placeholder 文字与着色（伪元素不能用 inline style，需 stylesheet 规则 + 类名）
        textarea.placeholder = placeholderText
        val placeholderStyle = document.createElement("style")
        placeholderStyle.textContent = ".mederi-ime-placeholder::placeholder { color: $placeholderColorHex; opacity: 1; }"
        document.head?.appendChild(placeholderStyle)
        textarea.classList.add("mederi-ime-placeholder")
        document.body?.appendChild(textarea)

        // 读 textarea 当前 value/选区 → 回灌 Compose（组合期不回灌，由调用方判断）
        fun flush() {
            val value = textarea.value
            val start = textarea.selectionStart ?: 0
            val end = textarea.selectionEnd ?: 0
            latestOnValueChange.value(TextFieldValue(value, TextRange(start, end)))
        }

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

        // Enter 发送：纯 Enter（无 Shift/Ctrl/Meta）触发 onSend（Compose onPreviewKeyEvent 在
        // wasmJs 不会触发——DOM 焦点在 textarea 上，键盘事件不进 Compose），Shift/Ctrl/Cmd+Enter 保留默认换行。
        // latestOnSend 避免 stale closure，同 latestOnValueChange 模式。
        textarea.addEventListener("keydown") { e ->
            val ke = e as KeyboardEvent
            if (ke.key == "Enter" && !ke.shiftKey && !ke.ctrlKey && !ke.metaKey) {
                val sendCb = latestOnSend.value
                if (sendCb != null) {
                    e.preventDefault()
                    sendCb()
                }
            }
        }

        onDispose {
            textarea.remove()
            placeholderStyle.remove()
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