package xyz.mederi.browser.obscura

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult

/**
 * CDP 交互层共享脚本与按键映射（ST3，`ObscuraCdpControl`/`ObscuraCdpLocator` 专用；
 * 观测落档 `core/build/obscura-cdp-control-test.json`）。
 *
 * 拆出动机：`ObscuraCdpControl` 文件 500 行上限拆分（项目规则），脚本模板与按键映射是
 * 纯静态资产（无状态），与交互原语同源同改，集中一处。
 *
 * - JS 脚本模板：表达式经 [JsonPrimitive] 编码注入（kotlinx 转义，防注入/转义坑；
 *   FakeCdpServer 按表达式 contains 匹配脚本化应答，表达式全文即测试锚点）。
 * - 按键映射：照 MCP 版 domKeyName 表复制（别动 MCP 版）；CDP `code`/`windowsVirtualKeyCode`
 *   为可选参数，字符键走 keyDown+insertText 简化（ST1 探针实测生效）。
 */

// ==================== JS 脚本模板 ====================

/** 快照 ref 解析（执行拍板：ref 一律走注入属性 querySelector；滚入视口后取真实布局中心）。 */
internal fun elementCenterScript(cssSelector: String): String =
    "(function(){var el=document.querySelector(${JsonPrimitive(cssSelector).toString()});" +
        "if(!el)return null;var r=el.getBoundingClientRect();" +
        "if(r.top<0||(r.bottom>window.innerHeight&&window.innerHeight>0))" +
        "{el.scrollIntoView({block:'center'});r=el.getBoundingClientRect();}" +
        "return JSON.stringify({found:true,cx:r.x+r.width/2,cy:r.y+r.height/2});})()"

/** fill：可填值元素设值 + input/change 冒泡事件（MCP 版语义保持）。 */
internal fun fillScript(cssSelector: String, text: String): String =
    "(function(){var el=document.querySelector(${JsonPrimitive(cssSelector).toString()});" +
        "if(!el)return '';" +
        "if('value' in el){el.focus();el.value=${JsonPrimitive(text).toString()};" +
        "el.dispatchEvent(new Event('input',{bubbles:true}));" +
        "el.dispatchEvent(new Event('change',{bubbles:true}));return 'filled';}" +
        "el.textContent=${JsonPrimitive(text).toString()};return 'text-set';})()"

/** waitFor 可见性探测（100ms 轮询；display/visibility/offsetParent，MCP 版 isVisible 同源判定）。 */
internal fun waitForScript(selector: String): String =
    "(function(){var el=document.querySelector(${JsonPrimitive(selector).toString()});" +
        "if(!el)return '0';var s=getComputedStyle(el);" +
        "if(s.display==='none'||s.visibility==='hidden')return '0';" +
        "return (el.offsetParent!==null||s.position==='fixed')?'1':'0';})()"

/** 属性选择器转义（MCP 版 escapeAttr 复制）。 */
internal fun escapeAttr(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

// ==================== 按键映射（MCP 版 domKeyName 表复制）====================

internal val CHAR_KEYS = setOf(
    KeyboardKey.A, KeyboardKey.C, KeyboardKey.V, KeyboardKey.X, KeyboardKey.S,
    KeyboardKey.Z, KeyboardKey.SPACE,
)

/** KeyboardKey → CDP key 名。 */
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

internal fun charOf(key: KeyboardKey): String = when (key) {
    KeyboardKey.SPACE -> " "
    KeyboardKey.A -> "a"
    KeyboardKey.C -> "c"
    KeyboardKey.V -> "v"
    KeyboardKey.X -> "x"
    KeyboardKey.S -> "s"
    KeyboardKey.Z -> "z"
    else -> ""
}

/** CSS code 名（CDP keyEvent.code 可选参数）。 */
internal fun cssCodeOf(domKey: String): String = when (domKey) {
    " " -> "Space"
    in "a".."z" -> "Key$domKey"
    in "0".."9" -> "Digit$domKey"
    else -> domKey
}

/** windowsVirtualKeyCode（CDP keyEvent 可选参数；字符键 = ASCII 码）。 */
internal fun virtualKeyCodeOf(key: KeyboardKey): Int = when (key) {
    KeyboardKey.ENTER -> 13
    KeyboardKey.TAB -> 9
    KeyboardKey.ESCAPE -> 27
    KeyboardKey.BACKSPACE -> 8
    KeyboardKey.DELETE -> 46
    KeyboardKey.ARROW_LEFT -> 37
    KeyboardKey.ARROW_UP -> 38
    KeyboardKey.ARROW_RIGHT -> 39
    KeyboardKey.ARROW_DOWN -> 40
    KeyboardKey.SHIFT -> 16
    KeyboardKey.CONTROL -> 17
    KeyboardKey.ALT -> 18
    KeyboardKey.META -> 91
    KeyboardKey.SPACE -> 32
    KeyboardKey.HOME -> 36
    KeyboardKey.END -> 35
    KeyboardKey.PAGE_UP -> 33
    KeyboardKey.PAGE_DOWN -> 34
    KeyboardKey.INSERT -> 45
    else -> if (key in CHAR_KEYS) charOf(key).first().code else 0
}

// ==================== CDP 输入原语（Control/Locator 复用；无状态，client+session 显式传入）====================

/** CDP 命令失败统一映射：CdpCommandException → Failure(recoverable=true)；取消原样透传。 */
internal suspend fun cdpGuard(action: String, block: suspend () -> OperationResult): OperationResult = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: CdpCommandException) {
    OperationResult.Failure(action, e.message ?: "$action command failed", recoverable = true)
}

/** 真实鼠标点击：mouseMoved → mousePressed(left, clickCount=1) → mouseReleased（ST1 探针实测命中）。 */
internal suspend fun cdpMouseClick(
    client: ObscuraCdpClient,
    x: Double,
    y: Double,
    s: String,
): OperationResult = cdpGuard("click") {
    client.send(
        "Input.dispatchMouseEvent",
        buildJsonObject {
            put("type", "mouseMoved")
            put("x", JsonPrimitive(x))
            put("y", JsonPrimitive(y))
        },
        s,
    )
    client.send(
        "Input.dispatchMouseEvent",
        buildJsonObject {
            put("type", "mousePressed")
            put("x", JsonPrimitive(x))
            put("y", JsonPrimitive(y))
            put("button", "left")
            put("clickCount", 1)
        },
        s,
    )
    client.send(
        "Input.dispatchMouseEvent",
        buildJsonObject {
            put("type", "mouseReleased")
            put("x", JsonPrimitive(x))
            put("y", JsonPrimitive(y))
            put("button", "left")
            put("clickCount", 1)
        },
        s,
    )
    OperationResult.Success("click")
}

/** 真实滚轮：mouseWheel（ST1 实测 1:1 滚动）。 */
internal suspend fun cdpMouseWheel(
    client: ObscuraCdpClient,
    x: Double,
    y: Double,
    deltaX: Int,
    deltaY: Int,
    s: String,
): OperationResult = cdpGuard("scroll") {
    client.send(
        "Input.dispatchMouseEvent",
        buildJsonObject {
            put("type", "mouseWheel")
            put("x", JsonPrimitive(x))
            put("y", JsonPrimitive(y))
            put("deltaX", JsonPrimitive(deltaX.toDouble()))
            put("deltaY", JsonPrimitive(deltaY.toDouble()))
            put("pointerType", "mouse")
        },
        s,
    )
    OperationResult.Success("scroll")
}

/** 悬停：mouseMoved 到坐标（MCP 版缺失的核心补齐）。 */
internal suspend fun cdpHover(
    client: ObscuraCdpClient,
    x: Double,
    y: Double,
    s: String,
): OperationResult = cdpGuard("hover") {
    client.send(
        "Input.dispatchMouseEvent",
        buildJsonObject {
            put("type", "mouseMoved")
            put("x", JsonPrimitive(x))
            put("y", JsonPrimitive(y))
            put("button", "none")
        },
        s,
    )
    OperationResult.Success("hover")
}

/**
 * 按键序列：keyDown →（字符键 insertText）→ keyUp（ST1 探针 keyDown-seq+insertText 生效实测）。
 * 键名映射见 [domKeyName]；windowsVirtualKeyCode/code 为 CDP 可选参数。
 */
internal suspend fun cdpKeyPress(
    client: ObscuraCdpClient,
    key: KeyboardKey,
    s: String,
): OperationResult = cdpGuard("press") {
    val domKey = domKeyName(key)
    val vk = virtualKeyCodeOf(key)
    val isChar = key in CHAR_KEYS
    client.send(
        "Input.dispatchKeyEvent",
        buildJsonObject {
            put("type", "keyDown")
            put("key", domKey)
            put("code", cssCodeOf(domKey))
            put("windowsVirtualKeyCode", vk)
            put("nativeVirtualKeyCode", vk)
            if (isChar) put("text", charOf(key))
        },
        s,
    )
    if (isChar) {
        client.send(
            "Input.insertText",
            buildJsonObject { put("text", charOf(key)) },
            s,
        )
    }
    client.send(
        "Input.dispatchKeyEvent",
        buildJsonObject {
            put("type", "keyUp")
            put("key", domKey)
            put("code", cssCodeOf(domKey))
            put("windowsVirtualKeyCode", vk)
            put("nativeVirtualKeyCode", vk)
            if (isChar) put("text", charOf(key))
        },
        s,
    )
    OperationResult.Success("press")
}
