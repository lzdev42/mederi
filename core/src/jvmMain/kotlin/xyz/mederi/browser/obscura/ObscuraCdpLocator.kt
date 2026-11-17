package xyz.mederi.browser.obscura

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import xyz.mederi.browser.BrowserLocator
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult

/**
 * Obscura CDP 版 [BrowserLocator]（ST3；观测落档 `core/build/obscura-cdp-control-test.json`）。
 *
 * 定位表达式两种（由 [ObscuraCdpControl.locator] 家族构造；语义 kind 命中 refIndex 后落到
 * `[data-mederi-ref="eN"]` CSS 表达式）：
 * - CSS：`document.querySelectorAll`（NodeList）驱动；
 * - XPATH：`document.evaluate`（ORDERED_NODE_SNAPSHOT）驱动；两者经同一解析表头
 *   （`typeof m.length==='number'` 区分 NodeList/XPathResult，Kotlin 侧零分叉）。
 *
 * 操作实现（复用 [ObscuraCdpControl] 内部原语，坐标走真实布局 getBoundingClientRect，禁 DOMSnapshot）：
 * - click/hover/scroll = 元素真实布局中心 + CDP 输入事件（mousePressed 序列 / mouseMoved /
 *   真实滚轮 mouseWheel——执行拍板"wheel 元素中心"）；点击/滚轮前先滚入视口。
 * - fill/type = evaluate 设值 + input/change 冒泡事件（type 语义 = 先清空再逐字，覆盖型设值等价，拍板）；
 *   press = 元素 focus 后走页面级 pressKeySequence；check = evaluate checkbox/radio 切换（MCP 版脚本同源）。
 * - getText/getAttribute/count/isVisible = evaluate 直读；
 *   nth/first/last = 全匹配列表 0-based 索引（last = 末位；契约"第 i 个匹配元素"语义。
 *   与 MCP 版 :nth-of-type 字符串拼接的差异见完成报告偏差清单——拼接与 XPath 不兼容且属性选择器下错位）。
 * - filter(hasText) = 文本过滤定位器（全匹配中 textContent 包含者；MCP 版 TextFilteredLocator 语义；
 *   CDP 版坐标仍走真实鼠标事件，无 evaluate 兜底点击；nth/first/last **保留过滤**——MCP 版丢失过滤是
 *   其实现缺陷，本层不复制）。
 * - **hover 是 CDP 版核心补齐（MCP 版 UnsupportedOperationException，ST 目标之一）**。
 */
open class ObscuraCdpLocator internal constructor(
    private val control: ObscuraCdpControl,
    private val expression: String,
    private val mode: Mode,
    internal val index: Int = 0,
    internal val hasText: String? = null,
) : BrowserLocator {

    internal enum class Mode { CSS, XPATH }

    // ── 交互（真实坐标 + CDP 输入事件）────────────────────────

    override suspend fun click(): OperationResult =
        centerAction("click") { c, s -> val (cx, cy) = c; control.clickAt(cx, cy, s) }

    override suspend fun hover(): OperationResult =
        centerAction("hover") { c, s -> val (cx, cy) = c; control.hoverAt(cx, cy, s) }

    /** 以元素中心为原点的真实滚轮（执行拍板：wheel 元素中心，真实滚轮事件）。 */
    override suspend fun scroll(deltaX: Int, deltaY: Int): OperationResult =
        centerAction("scroll") { c, s -> val (cx, cy) = c; control.scrollAt(cx, cy, deltaX, deltaY, s) }

    /** 无匹配 → Failure（非异常）；命中 → 滚入视口后取真实布局中心执行 [doAction]。 */
    private suspend fun centerAction(
        action: String,
        doAction: suspend (Pair<Double, Double>, String) -> OperationResult,
    ): OperationResult {
        val s = control.ensureSession()
        val center = control.evalString(centerScript(), s)
        val pos = parseCenter(center)
        return if (pos == null) {
            OperationResult.Failure(action, "no match for $expression", recoverable = true)
        } else {
            doAction(pos, s)
        }
    }

    // ── 输入（evaluate 设值 + 事件；press 走 CDP 按键序列）────────────────────────

    override suspend fun fill(text: String): OperationResult = setAndType("fill", text)

    /** type 语义 = 先清空再逐字：覆盖型设值 + input 事件（拍板，与 fill 同源实现）。 */
    override suspend fun type(text: String): OperationResult = setAndType("type", text)

    private suspend fun setAndType(action: String, text: String): OperationResult {
        val s = control.ensureSession()
        val out = control.evalString(
            bodyScript(
                "return (function(){if(!el)return '';" +
                    "if('value' in el){el.focus();el.value=${JsonPrimitive(text).toString()};" +
                    "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "el.dispatchEvent(new Event('change',{bubbles:true}));return 'filled';}" +
                    "el.textContent=${JsonPrimitive(text).toString()};return 'text-set'})()",
            ),
            s,
        )
        return if (out.isEmpty()) OperationResult.Failure(action, "no match for $expression", recoverable = true)
        else OperationResult.Success(action, detail = out)
    }

    /** 聚焦元素后按下按键（元素级；按键走 CDP keyDown/insertText/keyUp 序列）。 */
    override suspend fun press(key: KeyboardKey): OperationResult {
        val s = control.ensureSession()
        val out = control.evalString(bodyScript("if(el){el.focus();return 'focused'}return ''"), s)
        if (out.isEmpty()) return OperationResult.Failure("press", "no match for $expression", recoverable = true)
        return control.pressKeySequence(key, s)
    }

    /** 勾选（checkbox/radio；uncheck 同语义——底层为点击切换，MCP 版 check 脚本同源）。 */
    override suspend fun check(): OperationResult {
        val s = control.ensureSession()
        val out = control.evalString(
            bodyScript(
                "return (function(){if(!el)return 'no-element';" +
                    "if(el.type==='checkbox'||el.type==='radio'){if(!el.checked)el.click();return 'checked';}" +
                    "el.click();return 'clicked'})()",
            ),
            s,
        )
        return when (out) {
            "no-element" -> OperationResult.Failure("check", "no match for $expression", recoverable = true)
            else -> OperationResult.Success("check", detail = out)
        }
    }

    // ── 读（evaluate 直读）─────────────────────────────────────

    override suspend fun getText(): String {
        val s = control.ensureSession()
        return control.evalString(bodyScript("return el?(el.textContent||''):''"), s)
    }

    override suspend fun getAttribute(name: String): String? {
        val s = control.ensureSession()
        val out = control.evalString(bodyScript("return el?(el.getAttribute(${JsonPrimitive(name).toString()})||''):''"), s)
        return out.ifEmpty { null }
    }

    override suspend fun count(): Int {
        val s = control.ensureSession()
        // 过滤定位器计过滤命中数（MCP 版恒 1/0 是实现缺陷，不复制）
        val out = control.evalString(bodyScript("return ht!==null?f.length:n"), s)
        return out.toDoubleOrNull()?.toInt() ?: 0
    }

    override suspend fun isVisible(): Boolean {
        val s = control.ensureSession()
        val out = control.evalString(
            bodyScript(
                "return (function(){if(!el)return 'false';var s=getComputedStyle(el);" +
                    "return (s.display!=='none'&&s.visibility!=='hidden'&&(el.offsetParent!==null||" +
                    "s.position==='fixed'))?'true':'false'})()",
            ),
            s,
        )
        return out.trim().equals("true", ignoreCase = true)
    }

    // ── 派生定位器 ─────────────────────────────────────────────

    /** 第 i 个匹配（0-based，契约语义；越界 → 空定位行为，操作自然失败不抛）。 */
    open override suspend fun nth(i: Int): BrowserLocator = derived(i.coerceAtLeast(0), hasText)

    open override suspend fun first(): BrowserLocator = derived(0, hasText)

    open override suspend fun last(): BrowserLocator = derived(Int.MAX_VALUE, hasText)

    /** 文本包含过滤（MCP 版 TextFilteredLocator 语义；CDP 实现保留过滤 + 真实坐标操作）。 */
    open override suspend fun filter(hasText: String): BrowserLocator = derived(index, hasText)

    /** [index] 派生；hasText 非 null → [TextFilteredLocator]（过滤语义），否则普通定位器。 */
    private fun derived(i: Int, text: String?): BrowserLocator =
        if (text != null) CdpTextFilteredLocator(control, expression, mode, i, text)
        else ObscuraCdpLocator(control, expression, mode, i, null)

    // ── 脚本模板（表达式/文本经 JsonPrimitive 编码注入——kotlinx 转义，禁止手拼）────────────

    /**
     * 解析表头：`m` 全匹配表（NodeList/XPathResult 同形处理）、`n` 计数、`el` = 第 [index] 个匹配
     * （last=[Int.MAX_VALUE] → 末位；[hasText] 非 null 时在匹配内按 textContent 包含取首个）。
     * 越界/无匹配 → `el = null`（body 自判）。
     */
    private fun resolverHeader(): String {
        val listExpr = when (mode) {
            Mode.CSS -> "document.querySelectorAll(${JsonPrimitive(expression).toString()})"
            Mode.XPATH -> "document.evaluate(${JsonPrimitive(expression).toString()},document,null,9,null)"
        }
        val last = index == Int.MAX_VALUE
        val hasTextJson = hasText?.let { JsonPrimitive(it).toString() } ?: "null"
        return "(function(){var m=$listExpr;" +
            "var n=(typeof m.length==='number')?m.length:m.snapshotLength;" +
            "var at=$index;if($last)at=n-1;" +
            "var el=null;if(at>=0&&at<n&&n>0)el=(typeof m.item==='function')?m.item(at):m.snapshotItem(at);" +
            "var ht=$hasTextJson;" +
            "if(ht!==null){var f=[];" +
            "for(var k=0;k<n;k++){var e=(typeof m.item==='function')?m.item(k):m.snapshotItem(k);" +
            "if((e.textContent||'').indexOf(ht)>=0)f.push(e)}" +
            "var fat=$last?(f.length?f.length-1:0):(at>=0?at:0);" +
            "el=(fat<f.length)?f[fat]:null}"
    }

    /**
     * body 追加在解析表头之后，外层 `(function(){...})()` 闭包收尾（[resolverHeader] 不闭尾，
     * 由本方法的尾部 `})()` 收掉外层函数）。
     * body 两种形态：裸语句（自带 `return`，如 count/getText）；**完整 IIFE 表达式必须以
     * `return ` 开头**（外层闭包返回其结果——缺 `return` 时整体求值为 undefined，
     * evalString 得空串被误判"no match"，ST6 真二进制 e2e 实测暴露，fill/type/check/isVisible 同源修复）。
     */
    private fun bodyScript(body: String): String =
        resolverHeader() + body + "})()"

    private fun centerScript(): String =
        resolverHeader() +
            "if(!el)return '';" +
            "var r=el.getBoundingClientRect();" +
            "if(r.top<0||(r.bottom>window.innerHeight&&window.innerHeight>0))" +
            "{el.scrollIntoView({block:'center'});r=el.getBoundingClientRect();}" +
            "return JSON.stringify({cx:r.x+r.width/2,cy:r.y+r.height/2});})()"

    /** 中心应答解析（`{cx,cy}` JSON 字符串；数值走 Double，ST1 探针数值字面量惯例）。 */
    private fun parseCenter(center: String): Pair<Double, Double>? {
        if (center.isEmpty()) return null
        val obj = runCatching { JSON.parseToJsonElement(center).jsonObject }.getOrNull() ?: return null
        val cx = (obj["cx"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
        val cy = (obj["cy"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
        return cx to cy
    }
}

/**
 * 文本过滤定位器（MCP 版 TextFilteredLocator 的 CDP 实现）：全匹配中 textContent 包含 [hasText]
 * 的首个元素；操作仍走真实坐标 CDP 输入事件（无 evaluate 兜底点击）。nth/first/last 保留过滤语义
 * （MCP 版此处丢过滤，属实现缺陷，不复制）。
 */
private class CdpTextFilteredLocator(
    private val ctl: ObscuraCdpControl,
    private val expr: String,
    private val m: ObscuraCdpLocator.Mode,
    private val idx: Int,
    private val ht: String,
) : ObscuraCdpLocator(ctl, expr, m, idx, ht) {

    override suspend fun nth(i: Int): BrowserLocator =
        CdpTextFilteredLocator(ctl, expr, m, i.coerceAtLeast(0), ht)

    override suspend fun first(): BrowserLocator =
        CdpTextFilteredLocator(ctl, expr, m, 0, ht)

    override suspend fun last(): BrowserLocator =
        CdpTextFilteredLocator(ctl, expr, m, Int.MAX_VALUE, ht)

    override suspend fun filter(hasText: String): BrowserLocator =
        CdpTextFilteredLocator(ctl, expr, m, idx, hasText)
}

/** 语义定位器（TEXT/ROLE）未命中：操作统一 Failure(recoverable=false)，照 MCP 版模式。 */
internal class MissingLocator(
    private val kind: String,
    private val value: String,
) : BrowserLocator {
    private val failure = OperationResult.Failure(
        "locator",
        "no match for $kind:$value in current snapshot",
        recoverable = false,
    )
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
