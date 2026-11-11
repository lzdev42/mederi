package xyz.mederi.browser.obscura

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mederi.browser.BrowserControl
import xyz.mederi.browser.BrowserLocator
import xyz.mederi.browser.LocatorKind
import xyz.mederi.browser.PageSnapshot
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult
import java.util.Base64

/**
 * Obscura MCP 后端 [BrowserControl] 实现（collector 观测协议版，ST3）。
 *
 * 参数名与 snapshot 应答格式以实测落档为准：`core/build/obscura-schemas.json`
 * （38 工具 inputSchema）与 `core/build/obscura-snapshot-sample.txt`
 * （`browser_navigate(about:blank)` 后 `browser_snapshot(max_chars=20000)` 全文）。
 * 实测要点：
 * - `browser_snapshot{max_chars}` 应答首两行为 `URL: <url>` / `Title: <title>`，
 *   其后为元素行（`  - <role> "<name>" [ref=eN]`，含 textbox/heading 等）。
 * - 工具参数：navigate{url}、click{ref|selector}、fill{ref|selector,value}、
 *   type{ref|selector,text}、press_key{key,selector?}、evaluate{expression[,timeoutMs]}、
 *   wait_for{selector,timeout(秒)}、scroll{direction,amount,ref?,selector?}、
 *   get_attribute{ref|selector,attribute}、count{selector}、
 *   tab_new{url?}/tab_list{}/tab_switch{tab_id}/tab_close{tab_id}、screenshot{width?,height?}。
 *
 * [sessionLifecycleOwner]：true 时本实例拥有会话生命周期——[start] 对
 * [ObscuraMcpClient] 调用 `client.start()`；false 时为 Fake/外部注入会话，
 * start/close 不碰进程语义（close 仍幂等调用 session.close()）。
 * uploadFile/uploadFileBySelector 不覆盖，保持契约 default UnsupportedOperationException。
 */
class ObscuraBrowserControl(
    private val session: ObscuraMcpSession,
    private val sessionLifecycleOwner: Boolean = false,
    private val client: ObscuraMcpClient? = null
) : BrowserControl {

    private var closed = false

    override suspend fun start() {
        if (sessionLifecycleOwner) {
            val c = client ?: session as? ObscuraMcpClient
            c?.start()
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    override suspend fun getCurrentUrl(): String =
        parseSnapshot(session.callTool("browser_snapshot", snapshotArgs(0))).url

    override suspend fun getTitle(): String =
        parseSnapshot(session.callTool("browser_snapshot", snapshotArgs(0))).title

    override suspend fun snapshot(): PageSnapshot {
        val text = textOf(session.callTool("browser_snapshot", snapshotArgs(20000)))
        val parsed = parseSnapshotText(text)
        return PageSnapshot(url = parsed.url, title = parsed.title, yaml = text)
    }

    override suspend fun navigate(url: String): OperationResult =
        mapResult("navigate", session.callTool("browser_navigate", buildJsonObject { put("url", url) }))

    override suspend fun click(elementRef: String): OperationResult =
        mapResult("click", session.callTool("browser_click", buildJsonObject { put("ref", elementRef) }))

    override suspend fun clickByCoordinates(x: Int, y: Int): OperationResult {
        val script = "(function(){var e=document.elementFromPoint($x,$y);if(e){e.click();return 'clicked'}return 'no-element'})()"
        return mapResult("click", session.callTool("browser_evaluate", buildJsonObject { put("expression", script) }))
    }

    override suspend fun type(elementRef: String, text: String): OperationResult =
        mapResult("type", session.callTool("browser_fill", buildJsonObject {
            put("ref", elementRef)
            put("value", text)
        }))

    override suspend fun press(key: KeyboardKey): OperationResult =
        mapResult("press", session.callTool("browser_press_key", buildJsonObject { put("key", domKeyName(key)) }))

    override suspend fun scroll(elementRef: String, deltaX: Int, deltaY: Int): OperationResult {
        // browser_scroll 无 deltaX/deltaY 数值参数：方向/amount 模型与坐标滚动无对应维度，
        // 按语义取主导方向后以 browser_evaluate window.scrollBy 兜底精确滚动该 ref 元素。
        val script = "(function(){var e=null;try{e=document.querySelector('[ref=\"'+${
            JsonPrimitive(elementRef).toString()
        }'\"]')}catch(_){}if(e){e.scrollBy($deltaX,$deltaY);return 'scrolled'}window.scrollBy($deltaX,$deltaY);return 'scrolled-page'})()"
        return mapResult("scroll", session.callTool("browser_evaluate", buildJsonObject { put("expression", script) }))
    }

    override suspend fun scrollPage(deltaX: Int, deltaY: Int): OperationResult {
        // browser_scroll 方向/amount 不支持任意 (deltaX,deltaY) 二维增量，evaluate 兜底。
        val script = "window.scrollBy($deltaX,$deltaY)"
        return mapResult("scroll_page", session.callTool("browser_evaluate", buildJsonObject { put("expression", script) }))
    }

    override suspend fun scrollByCoordinates(originX: Int, originY: Int, deltaX: Int, deltaY: Int): OperationResult {
        // browser_scroll 无坐标原点参数，按 viewport 语义以 evaluate 兜底（origin 仅记录于脚本注释）。
        val script = "/*origin=$originX,$originY*/window.scrollBy($deltaX,$deltaY)"
        return mapResult("scroll", session.callTool("browser_evaluate", buildJsonObject { put("expression", script) }))
    }

    override suspend fun waitFor(selector: String, timeoutMs: Long): OperationResult =
        mapResult("wait_for", session.callTool("browser_wait_for", buildJsonObject {
            put("selector", selector)
            // schema timeout 单位为秒
            put("timeout", timeoutMs / 1000.0)
        }))

    override suspend fun screenshot(): ByteArray? {
        val result = session.callTool("browser_screenshot", JsonObject(emptyMap()))
        val image = result.content.filterIsInstance<ImageContent>().firstOrNull() ?: return null
        return Base64.getDecoder().decode(image.data)
    }

    override suspend fun evaluateJavascript(js: String): String =
        textOf(session.callTool("browser_evaluate", buildJsonObject { put("expression", js) }))

    override suspend fun openTab(): OperationResult =
        mapResult("open_tab", session.callTool("browser_tab_new", JsonObject(emptyMap())))

    override suspend fun closeTab(tabId: String): OperationResult =
        mapResult("close_tab", session.callTool("browser_tab_close", buildJsonObject { put("tab_id", tabId) }))

    override suspend fun listTabs(): List<String> {
        val text = textOf(session.callTool("browser_tab_list", JsonObject(emptyMap())))
        return parseTabIds(text)
    }

    override suspend fun selectTab(tabId: String): OperationResult =
        mapResult("select_tab", session.callTool("browser_tab_switch", buildJsonObject { put("tab_id", tabId) }))

    override suspend fun locator(selector: String): BrowserLocator =
        ObscuraBrowserLocator(session, selector, this)

    override suspend fun locator(kind: LocatorKind, value: String, name: String?, exact: Boolean): BrowserLocator {
        return when (kind) {
            LocatorKind.CSS -> locator(value)
            LocatorKind.XPATH -> locator(value)
            LocatorKind.PLACEHOLDER -> locator("[placeholder=\"${escapeAttr(value)}\"]")
            LocatorKind.TEST_ID -> locator("[data-testid=\"${escapeAttr(value)}\"]")
            LocatorKind.ALT_TEXT -> locator("[alt=\"${escapeAttr(value)}\"]")
            LocatorKind.TITLE -> locator("[title=\"${escapeAttr(value)}\"]")
            LocatorKind.LABEL -> locator("[aria-label=\"${escapeAttr(value)}\"]")
            LocatorKind.TEXT, LocatorKind.ROLE -> {
                val ref = findRefByText(value, name, exact, kind)
                if (ref == null) {
                    // 找不到：返回一个失败定位器，点击等操作返回 Failure(recoverable=false)
                    MissingLocator(kind, value)
                } else {
                    locator(ref)
                }
            }
        }
    }

    /** TEXT/ROLE：在 snapshot 文本行中匹配可见文本/可访问名，取该行 ref（`ref=eN`）。 */
    private suspend fun findRefByText(value: String, name: String?, exact: Boolean, kind: LocatorKind): String? {
        val snapshotText = textOf(session.callTool("browser_snapshot", snapshotArgs(20000)))
        val needle = name ?: value
        for (line in snapshotText.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("-")) continue
            // 行格式实测：- role "name" [ref=eN]（name 可能带引号）
            val refMatch = REF_REGEX.find(trimmed) ?: continue
            val quoted = QUOTED_REGEX.find(trimmed)?.groupValues?.get(1)
            val candidate = quoted ?: trimmed
            val matches = if (kind == LocatorKind.ROLE) {
                // ROLE：首词为 role，value=role，可选 name=可访问名
                val role = trimmed.removePrefix("-").trim().substringBefore(" ")
                role.equals(value, ignoreCase = true) && (name == null || candidate == name || candidate.contains(name))
            } else {
                if (exact) candidate == needle else candidate.contains(needle)
            }
            if (matches) return refMatch.groupValues[1]
        }
        return null
    }

    private class MissingLocator(private val kind: LocatorKind, private val value: String) : BrowserLocator {
        private val failure = OperationResult.Failure("locator", "no match for $kind:$value", recoverable = false)
        override suspend fun click(): OperationResult = failure
        override suspend fun fill(text: String): OperationResult = failure
        override suspend fun hover(): OperationResult = failure
        override suspend fun getText(): String = ""
        override suspend fun getAttribute(name: String): String? = null
        override suspend fun count(): Int = 0
        override suspend fun isVisible(): Boolean = false
        override suspend fun nth(i: Int): BrowserLocator = this
        override suspend fun first(): BrowserLocator = this
        override suspend fun last(): BrowserLocator = this
        override suspend fun filter(hasText: String): BrowserLocator = this
        override suspend fun press(key: KeyboardKey): OperationResult = failure
        override suspend fun check(): OperationResult = failure
        override suspend fun scroll(deltaX: Int, deltaY: Int): OperationResult = failure
        override suspend fun type(text: String): OperationResult = failure
    }

    // ── 内部工具 ──

    private fun snapshotArgs(maxChars: Int): JsonObject = buildJsonObject { put("max_chars", maxChars) }

    internal fun textOf(result: CallToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString("") { it.text }

    internal fun mapResult(action: String, result: CallToolResult): OperationResult {
        val text = textOf(result)
        return if (result.isError == true) {
            OperationResult.Failure(action, text.take(500), recoverable = false)
        } else {
            OperationResult.Success(action, detail = text.take(500))
        }
    }

    internal data class ParsedSnapshot(val url: String, val title: String)

    internal fun parseSnapshot(result: CallToolResult): ParsedSnapshot = parseSnapshotText(textOf(result))

    internal fun parseSnapshotText(text: String): ParsedSnapshot {
        var url = ""
        var title = ""
        for (line in text.lines()) {
            when {
                line.startsWith("URL:") -> url = line.removePrefix("URL:").trim()
                line.startsWith("Title:") -> title = line.removePrefix("Title:").trim()
            }
        }
        return ParsedSnapshot(url, title)
    }

    internal fun parseTabIds(text: String): List<String> {
        // tab_list 应答格式未在样例落档：按行取首个非空 token 作为 tab id（兼容 "id: x"/"- x" 等行式输出）。
        return text.lines().map { it.trim().removePrefix("-").trim() }
            .mapNotNull { line ->
                when {
                    line.isEmpty() -> null
                    line.startsWith("id:", ignoreCase = true) -> line.removePrefix("id:").trim().substringBefore(" ").ifEmpty { null }
                    line.startsWith("tab_id:", ignoreCase = true) -> line.removePrefix("tab_id:").trim().substringBefore(" ").ifEmpty { null }
                    else -> line.substringBefore(" ").ifEmpty { null }
                }
            }
    }

    internal fun escapeAttr(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    internal fun domKeyName(key: KeyboardKey): String = when (key) {
        KeyboardKey.ENTER -> "Enter"
        KeyboardKey.TAB -> "Tab"
        KeyboardKey.ESCAPE -> "Escape"
        KeyboardKey.BACKSPACE -> "Backspace"
        KeyboardKey.DELETE -> "Delete"
        KeyboardKey.ARROW_UP -> "ArrowUp"
        KeyboardKey.ARROW_DOWN -> "ArrowDown"
        KeyboardKey.ARROW_LEFT -> "ArrowLeft"
        KeyboardKey.ARROW_RIGHT -> "ArrowRight"
        KeyboardKey.SHIFT -> "Shift"
        KeyboardKey.CONTROL -> "Control"
        KeyboardKey.ALT -> "Alt"
        KeyboardKey.META -> "Meta"
        KeyboardKey.SPACE -> " "
        KeyboardKey.HOME -> "Home"
        KeyboardKey.END -> "End"
        KeyboardKey.PAGE_UP -> "PageUp"
        KeyboardKey.PAGE_DOWN -> "PageDown"
        KeyboardKey.INSERT -> "Insert"
        KeyboardKey.F1 -> "F1"
        KeyboardKey.F2 -> "F2"
        KeyboardKey.F3 -> "F3"
        KeyboardKey.F4 -> "F4"
        KeyboardKey.F5 -> "F5"
        KeyboardKey.F6 -> "F6"
        KeyboardKey.F7 -> "F7"
        KeyboardKey.F8 -> "F8"
        KeyboardKey.F9 -> "F9"
        KeyboardKey.F10 -> "F10"
        KeyboardKey.F11 -> "F11"
        KeyboardKey.F12 -> "F12"
        KeyboardKey.A -> "a"
        KeyboardKey.C -> "c"
        KeyboardKey.V -> "v"
        KeyboardKey.X -> "x"
        KeyboardKey.S -> "s"
        KeyboardKey.Z -> "z"
    }

    private companion object {
        val REF_REGEX = Regex("""ref=(e\d+)""")
        val QUOTED_REGEX = Regex(""""([^"]*)"""")
    }
}

/**
 * Obscura 元素定位器：selector 直传各工具（CSS）。
 * 无 MCP 对应的方法：hover 保持 default UnsupportedOperationException（接口未提供 default，
 * 故此处显式抛 UnsupportedOperationException 并在报告列出）。
 */
class ObscuraBrowserLocator internal constructor(
    private val session: ObscuraMcpSession,
    private val selector: String,
    private val owner: ObscuraBrowserControl? = null
) : BrowserLocator {

    override suspend fun click(): OperationResult =
        mapResult("click", session.callTool("browser_click", buildJsonObject { put("selector", selector) }))

    override suspend fun fill(text: String): OperationResult =
        mapResult("fill", session.callTool("browser_fill", buildJsonObject {
            put("selector", selector)
            put("value", text)
        }))

    override suspend fun hover(): OperationResult =
        throw UnsupportedOperationException("hover not supported by Obscura MCP backend")

    override suspend fun getText(): String {
        val script = "(function(){var e=document.querySelector(${JsonPrimitive(selector).toString()});return e?(e.textContent||''):''})()"
        return evaluateSuspend(script)
    }

    override suspend fun getAttribute(name: String): String? {
        val result = session.callTool("browser_get_attribute", buildJsonObject {
            put("selector", selector)
            put("attribute", name)
        })
        if (result.isError == true) return null
        val text = textOf(result).trim()
        return text.ifEmpty { null }
    }

    override suspend fun count(): Int {
        val result = session.callTool("browser_count", buildJsonObject { put("selector", selector) })
        if (result.isError == true) return 0
        return textOf(result).trim().toIntOrNull() ?: 0
    }

    override suspend fun isVisible(): Boolean {
        val script = "(function(){var e=document.querySelector(${JsonPrimitive(selector).toString()});" +
            "if(!e)return false;var s=getComputedStyle(e);if(s.display==='none'||s.visibility==='hidden')return false;" +
            "return e.offsetParent!==null||s.position==='fixed'})()"
        return evaluateSuspend(script).trim().equals("true", ignoreCase = true)
    }

    override suspend fun nth(i: Int): BrowserLocator =
        ObscuraBrowserLocator(session, "$selector:nth-of-type(${i + 1})", owner)

    override suspend fun first(): BrowserLocator =
        ObscuraBrowserLocator(session, "$selector:first-of-type", owner)

    override suspend fun last(): BrowserLocator =
        ObscuraBrowserLocator(session, "$selector:last-of-type", owner)

    override suspend fun filter(hasText: String): BrowserLocator {
        // 文本过滤无 selector 表达：evaluate 驱动的过滤定位器（点击等走 evaluate 兜底）。
        return TextFilteredLocator(session, selector, hasText, owner)
    }

    override suspend fun press(key: KeyboardKey): OperationResult {
        val domKey = owner?.domKeyName(key) ?: throw IllegalArgumentException("unmapped key: $key")
        return mapResult("press", session.callTool("browser_press_key", buildJsonObject {
            put("key", domKey)
            put("selector", selector)
        }))
    }

    override suspend fun check(): OperationResult {
        // schema 无独立 check 工具：evaluate 内对 checkbox/radio 切换。
        val script = "(function(){var e=document.querySelector(${JsonPrimitive(selector).toString()});" +
            "if(!e)return 'no-element';if(e.type==='checkbox'||e.type==='radio'){if(!e.checked)e.click();return 'checked'}e.click();return 'clicked'})()"
        return mapResult("check", session.callTool("browser_evaluate", buildJsonObject { put("expression", script) }))
    }

    override suspend fun scroll(deltaX: Int, deltaY: Int): OperationResult {
        val script = "(function(){var e=document.querySelector(${JsonPrimitive(selector).toString()});" +
            "if(e){e.scrollBy($deltaX,$deltaY);return 'scrolled'}return 'no-element'})()"
        return mapResult("scroll", session.callTool("browser_evaluate", buildJsonObject { put("expression", script) }))
    }

    override suspend fun type(text: String): OperationResult =
        mapResult("type", session.callTool("browser_type", buildJsonObject {
            put("selector", selector)
            put("text", text)
        }))

    // ── 内部工具（与 owner 复用映射/文本提取）──

    private suspend fun evaluateSuspend(script: String): String = textOf(
        session.callTool("browser_evaluate", buildJsonObject { put("expression", script) })
    )

    private fun textOf(result: CallToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString("") { it.text }

    private fun mapResult(action: String, result: CallToolResult): OperationResult {
        val text = textOf(result)
        return if (result.isError == true) {
            OperationResult.Failure(action, text.take(500), recoverable = false)
        } else {
            OperationResult.Success(action, detail = text.take(500))
        }
    }
}

/** filter(hasText) 定位器：以 evaluate 文本过滤实现 click/fill 等操作。 */
private class TextFilteredLocator(
    private val session: ObscuraMcpSession,
    private val baseSelector: String,
    private val hasText: String,
    private val owner: ObscuraBrowserControl?
) : BrowserLocator {
    private fun script(body: String): String =
        "(function(){var els=document.querySelectorAll(${JsonPrimitive(baseSelector).toString()});" +
            "var t;for(var i=0;i<els.length;i++){if(((els[i].textContent||'').indexOf(${JsonPrimitive(hasText).toString()})>=0)){t=els[i];break}}" +
            "if(!t)return 'no-element';$body})()"

    private suspend fun evaluate(body: String): String = textOf(
        session.callTool("browser_evaluate", buildJsonObject { put("expression", script(body)) })
    )

    override suspend fun click(): OperationResult =
        mapResult("click", session.callTool("browser_evaluate", buildJsonObject { put("expression", script("t.click();return 'clicked'")) }))

    override suspend fun fill(text: String): OperationResult =
        mapResult("fill", session.callTool("browser_evaluate", buildJsonObject {
            put("expression", script("if('value' in t){t.focus();t.value=${JsonPrimitive(text).toString()};t.dispatchEvent(new Event('input',{bubbles:true}));return 'filled'}return 'no-value'"))
        }))

    override suspend fun hover(): OperationResult =
        throw UnsupportedOperationException("hover not supported by Obscura MCP backend")

    override suspend fun getText(): String = evaluate("return (t.textContent||'')")

    override suspend fun getAttribute(name: String): String? {
        val v = evaluate("var a=t.getAttribute(${JsonPrimitive(name).toString()});return a==null?'':a").trim()
        return v.ifEmpty { null }
    }

    override suspend fun count(): Int {
        val found = evaluate("return 'found'")
        return if (found.trim() == "found") 1 else 0
    }

    override suspend fun isVisible(): Boolean {
        val v = evaluate("var s=getComputedStyle(t);return (s.display!=='none'&&s.visibility!=='hidden'&&(t.offsetParent!==null||s.position==='fixed'))?'true':'false'")
        return v.trim().equals("true", ignoreCase = true)
    }

    override suspend fun nth(i: Int): BrowserLocator = ObscuraBrowserLocator(session, baseSelector, owner)
    override suspend fun first(): BrowserLocator = ObscuraBrowserLocator(session, baseSelector, owner)
    override suspend fun last(): BrowserLocator = ObscuraBrowserLocator(session, baseSelector, owner)
    override suspend fun filter(hasText: String): BrowserLocator = TextFilteredLocator(session, baseSelector, hasText, owner)

    override suspend fun press(key: KeyboardKey): OperationResult =
        OperationResult.Failure("press", "press not supported on text-filtered locator", recoverable = false)

    override suspend fun check(): OperationResult =
        mapResult("check", session.callTool("browser_evaluate", buildJsonObject { put("expression", script("if(t.type==='checkbox'||t.type==='radio'){if(!t.checked)t.click();return 'checked'}t.click();return 'clicked'")) }))

    override suspend fun scroll(deltaX: Int, deltaY: Int): OperationResult =
        mapResult("scroll", session.callTool("browser_evaluate", buildJsonObject { put("expression", script("t.scrollBy($deltaX,$deltaY);return 'scrolled'")) }))

    override suspend fun type(text: String): OperationResult =
        mapResult("type", session.callTool("browser_evaluate", buildJsonObject {
            put("expression", script("if('value' in t){t.focus();t.value=${JsonPrimitive(text).toString()};t.dispatchEvent(new Event('input',{bubbles:true}));return 'typed'}return 'no-value'"))
        }))

    private fun textOf(result: CallToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString("") { it.text }

    private fun mapResult(action: String, result: CallToolResult): OperationResult {
        val text = textOf(result)
        return if (result.isError == true) OperationResult.Failure(action, text.take(500), recoverable = false)
        else OperationResult.Success(action, detail = text.take(500))
    }
}
