package xyz.mederi

import xyz.kbrowser.webview.KeyboardKey as KbKeyboardKey
import xyz.kbrowser.webview.KBPage
import xyz.mederi.browser.BrowserControl
import xyz.mederi.browser.PageSnapshot
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置 JCEF 浏览器的 [BrowserControl] 适配：一个实例绑定一个 [KBPage]（= UI 上的一个 tab）。
 *
 * 生命周期语义（与设计文档一致）：UI 拥有 KBPage 生命周期——
 * - [start] / [close] 均为幂等空实现：不拉起进程、不杀页面。任务结束（finally 总调 close()）
 *   后 tab 仍保留，用户能继续看结果，手动关 tab 或应用退出时才回收。
 * - 操作全部落到对应 KBPage 上——即使 UI 切到别的 tab，AI 的操作仍然作用在绑定页面上。
 */
class JCEFBrowserControl(
    private val page: KBPage,
) : BrowserControl {

    @Volatile
    private var lastUrl: String = ""
    @Volatile
    private var lastTitle: String = ""

    override suspend fun start() {
        // 幂等空实现：页面由 UI 创建并渲染，无需启动进程
    }

    override suspend fun close() {
        // 幂等空实现：任务结束不杀页面，tab 由用户手动关闭或应用退出时回收
    }

    override suspend fun getCurrentUrl(): String = lastUrl

    override suspend fun getTitle(): String = lastTitle

    override suspend fun navigate(url: String): OperationResult {
        return try {
            withContext(Dispatchers.Main) { page.loadUrl(url) }
            lastUrl = url
            lastTitle = runCatching { pageTitle() }.getOrDefault("")
            OperationResult.Success(action = "navigate", verified = true, detail = url)
        } catch (e: Exception) {
            OperationResult.Failure(action = "navigate", reason = e.message ?: e.toString())
        }
    }

    override suspend fun click(elementRef: String): OperationResult =
        mapResult { page.click(elementRef) }

    override suspend fun clickByCoordinates(x: Int, y: Int): OperationResult =
        mapResult { page.clickByCoordinates(x, y) }

    override suspend fun type(elementRef: String, text: String): OperationResult {
        // KBPage 的 type 作用于当前焦点元素：先点击目标（聚焦）再逐字符输入
        return try {
            withContext(Dispatchers.Main) {
                val clickResult = page.click(elementRef).toCore()
                if (!clickResult.success) return@withContext clickResult
                page.type(text)
                OperationResult.Success(action = "type", verified = true, detail = text)
            }
        } catch (e: Exception) {
            OperationResult.Failure(action = "type", reason = e.message ?: e.toString())
        }
    }

    override suspend fun scroll(elementRef: String, deltaX: Int, deltaY: Int): OperationResult =
        mapResult { page.scroll(elementRef, deltaX, deltaY) }

    override suspend fun press(key: KeyboardKey): OperationResult {
        val mapped = mapKey(key) ?: return OperationResult.Failure(
            action = "press", reason = "不支持的按键 $key", recoverable = true
        )
        return try {
            withContext(Dispatchers.Main) {
                page.press(mapped)
                OperationResult.Success(action = "press", verified = false)
            }
        } catch (e: Exception) {
            OperationResult.Failure(action = "press", reason = e.message ?: e.toString())
        }
    }

    override suspend fun snapshot(): PageSnapshot {
        val result = withContext(Dispatchers.Main) { page.snapshot() }
        val raw = result.rawTree
        val url = raw.url.ifEmpty { lastUrl }
        return PageSnapshot(
            url = url,
            title = runCatching { pageTitle() }.getOrDefault("").ifEmpty { lastTitle },
            yaml = result.yaml,
            rawTree = raw
        )
    }

    override suspend fun screenshot(): ByteArray? =
        withContext(Dispatchers.Main) { runCatching { page.screenshot() }.getOrNull() }

    // ------------------------------------------------------------------

    private suspend fun mapResult(block: suspend () -> xyz.kbrowser.webview.OperationResult): OperationResult =
        try {
            withContext(Dispatchers.Main) { block() }.toCore()
        } catch (e: Exception) {
            OperationResult.Failure(action = "operation", reason = e.message ?: e.toString())
        }

    private suspend fun pageTitle(): String =
        runCatching { page.evaluateJavascript("document.title").trim().removeSurrounding("\"") }
            .getOrDefault("")

    private fun xyz.kbrowser.webview.OperationResult.toCore(): OperationResult = when (this) {
        is xyz.kbrowser.webview.OperationResult.Success ->
            OperationResult.Success(action = action, verified = verified, detail = detail)
        is xyz.kbrowser.webview.OperationResult.Failure ->
            OperationResult.Failure(action = action, reason = reason, recoverable = recoverable)
        is xyz.kbrowser.webview.OperationResult.Acknowledged -> OperationResult.Acknowledged
    }

    private fun mapKey(key: KeyboardKey): KbKeyboardKey? = when (key) {
        KeyboardKey.ENTER -> KbKeyboardKey.ENTER
        KeyboardKey.TAB -> KbKeyboardKey.TAB
        KeyboardKey.ESCAPE -> KbKeyboardKey.ESCAPE
        KeyboardKey.BACKSPACE -> KbKeyboardKey.BACKSPACE
        KeyboardKey.DELETE -> KbKeyboardKey.DELETE
        KeyboardKey.ARROW_UP -> KbKeyboardKey.ARROW_UP
        KeyboardKey.ARROW_DOWN -> KbKeyboardKey.ARROW_DOWN
        KeyboardKey.ARROW_LEFT -> KbKeyboardKey.ARROW_LEFT
        KeyboardKey.ARROW_RIGHT -> KbKeyboardKey.ARROW_RIGHT
        KeyboardKey.SHIFT -> KbKeyboardKey.SHIFT
        KeyboardKey.CONTROL -> KbKeyboardKey.CONTROL
        KeyboardKey.ALT -> KbKeyboardKey.ALT
        KeyboardKey.META -> KbKeyboardKey.META
        KeyboardKey.SPACE -> KbKeyboardKey.SPACE
        KeyboardKey.HOME -> KbKeyboardKey.HOME
        KeyboardKey.END -> KbKeyboardKey.END
        KeyboardKey.PAGE_UP -> KbKeyboardKey.PAGE_UP
        KeyboardKey.PAGE_DOWN -> KbKeyboardKey.PAGE_DOWN
        KeyboardKey.INSERT -> KbKeyboardKey.INSERT
        KeyboardKey.F1 -> KbKeyboardKey.F1
        KeyboardKey.F2 -> KbKeyboardKey.F2
        KeyboardKey.F3 -> KbKeyboardKey.F3
        KeyboardKey.F4 -> KbKeyboardKey.F4
        KeyboardKey.F5 -> KbKeyboardKey.F5
        KeyboardKey.F6 -> KbKeyboardKey.F6
        KeyboardKey.F7 -> KbKeyboardKey.F7
        KeyboardKey.F8 -> KbKeyboardKey.F8
        KeyboardKey.F9 -> KbKeyboardKey.F9
        KeyboardKey.F10 -> KbKeyboardKey.F10
        KeyboardKey.F11 -> KbKeyboardKey.F11
        KeyboardKey.F12 -> KbKeyboardKey.F12
        KeyboardKey.A -> KbKeyboardKey.A
        KeyboardKey.C -> KbKeyboardKey.C
        KeyboardKey.V -> KbKeyboardKey.V
        KeyboardKey.X -> KbKeyboardKey.X
        KeyboardKey.S -> KbKeyboardKey.S
        KeyboardKey.Z -> KbKeyboardKey.Z
    }
}
