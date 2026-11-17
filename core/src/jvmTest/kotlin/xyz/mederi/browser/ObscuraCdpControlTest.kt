package xyz.mederi.browser

import java.io.File
import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult
import xyz.mederi.browser.cdp.FakeCdpServer
import xyz.mederi.browser.obscura.ObscuraCdpClient
import xyz.mederi.browser.obscura.ObscuraCdpControl

/**
 * ObscuraCdpControl 单测（ST3，观测落档）：[FakeCdpServer] 脚本化驱动，不起真 obscura 进程
 * （经 [ObscuraCdpClient.connect] 注入 WS 端点，同 ST2 模式；真二进制验证在 ST6）。
 *
 * 顺序验证并逐项落档 `core/build/obscura-cdp-control-test.json`（4 空格缩进，照
 * obscura-cdp-client-test.json 落档模式；键值精确匹配验收字面量）：
 * - `navigate_waits_load`：服务端收到 Page.navigate 后、Page.loadEventFired 事件推送前
 *   navigate **不得返回**（验证"事件等待"语义而非只等应答）；事件到达后才返回 Success
 * - `click_real_mouse_events`：click(ref) → 服务端记录 Input.dispatchMouseEvent
 *   mouseMoved→mousePressed→mouseReleased，x/y = 元素 rect 中心（ref 经 data-mederi-ref
 *   注入属性 querySelector 解析）
 * - `type_key_events`：press(ENTER) → keyDown/keyUp；press(A) → keyDown(text=a)+
 *   Input.insertText+keyUp；locator.type → evaluate 设值+事件（fill 语义）；
 *   locator.press → focus + 按键序列
 * - `scroll_wheel_deltas`：scrollPage(0,400) → Input.dispatchMouseEvent type=mouseWheel
 *   deltaX=0 deltaY=400，原点 = evaluate 视口中心
 * - `tabs_via_target`：openTab → Target.createTarget（不自动切换）；listTabs →
 *   Target.getTargets 过滤 type=page；selectTab → Target.activateTarget + 重 attach；
 *   closeTab → Target.closeTarget
 * - `snapshot_ref_format`：快照首两行 `URL:`/`Title:` 前缀 + 元素行 `- <role> "name" [ref=eN]`
 *   + data-mederi-ref 注入 evaluate 被调用 + max_chars 小预算下正文截断（控件优先）
 *
 * 全部场景在一个 @Test 内顺序执行（单实例保观测 JSON 全键——照 ST2 测试模式）；
 * 每场景独立 FakeCdpServer + client + control（命令日志隔离），finally 一律关闭释放端口。
 */
class ObscuraCdpControlTest {

    private val obsFile = File("build/obscura-cdp-control-test.json")
    private val observations = linkedMapOf<String, String>()
    private val json = Json { prettyPrint = true }

    /** 观测落档（照 ObscuraCdpClientTest 的 record/persist 模式，每键即写）。 */
    private fun record(key: String, value: String) {
        observations[key] = value
        obsFile.parentFile?.mkdirs()
        obsFile.writeText(
            json.encodeToString(
                JsonObject.serializer(),
                JsonObject(observations.mapValues { JsonPrimitive(it.value) }),
            ),
        )
    }

    @Test
    fun `cdp control full contract`() = runBlocking {
        navigateWaitsLoad()
        clickRealMouseEvents()
        typeKeyEvents()
        scrollWheelDeltas()
        tabsViaTarget()
        snapshotRefFormat()
    }

    // ==================== 测试基建 ====================

    /** Runtime.evaluate 字符串应答（探针 shape：result.type=string + value）。 */
    private fun evalStringResult(value: String): JsonObject = buildJsonObject {
        put("result", buildJsonObject {
            put("type", "string")
            put("value", JsonPrimitive(value))
        })
    }

    /** 为无脚本 method 注册成功空应答（FakeCdpServer 对无脚本 method 回 -32601 error）。 */
    private fun FakeCdpServer.succeed(vararg methods: String) {
        methods.forEach { onMethod(it) { buildJsonObject {} } }
    }

    /** 标准场景起法：脚本 Target.createTarget（page-1）+ connect 注入 + 构造 control。 */
    private fun newControl(
        server: FakeCdpServer,
        loadWaitMs: Long = 3_000L,
        maxChars: Int = ObscuraCdpControl.DEFAULT_MAX_CHARS,
    ): ObscuraCdpControl {
        server.onMethod("Target.createTarget") { buildJsonObject { put("targetId", "page-1") } }
        val client = ObscuraCdpClient.connect(server.endpoint, commandTimeoutMs = 5_000)
        return ObscuraCdpControl(client, loadWaitMs = loadWaitMs, maxChars = maxChars)
    }

    private suspend fun newScenario(): FakeCdpServer {
        val server = FakeCdpServer(attachSessionId = "page-1-session-1")
        server.start()
        return server
    }

    // ==================== 场景 1：navigate_waits_load ====================

    private suspend fun navigateWaitsLoad() {
        val server = newScenario()
        try {
            server.succeed("Page.enable")
            server.onMethod("Page.navigate") { params ->
                buildJsonObject {
                    put("frameId", "frame-1")
                    put("loaderId", "loader-1")
                    put("url", params["url"] ?: JsonPrimitive("<missing>"))
                }
            }
            val control = newControl(server)
            try {
                control.start()
                assertEquals("page-1-session-1", control.sessionId, "start 后必须持有显式 attach session")

                val r = coroutineScope {
                    val result = CompletableDeferred<OperationResult>()
                    launch { result.complete(control.navigate("https://example.com/")) }
                    // 等 navigate 命令确实到达服务端（应答会立即返回——若只等应答，命令到即完成）
                    withTimeout(5_000) {
                        while (server.receivedCommandsFor("Page.navigate").isEmpty()) delay(10)
                    }
                    // loadEventFired 尚未推送：navigate 不得返回（事件等待语义，ST2 竞态防护同款）
                    assertFalse(result.isCompleted, "loadEventFired 事件到达前 navigate 不得返回")
                    // 事件到达 → 才放行
                    server.pushEvent(
                        "Page.loadEventFired",
                        buildJsonObject { put("timestamp", 1.5) },
                        sessionId = "page-1-session-1",
                    )
                    withTimeout(5_000) { result.await() }
                }
                assertTrue(r is OperationResult.Success, "navigate 应 Success（事件已到达）: $r")
                // 命令顺序：Page.enable（保 load 事件流）先于 Page.navigate
                val cmds = server.receivedCommands
                val enableIdx = cmds.indexOfFirst { it.method == "Page.enable" }
                val navIdx = cmds.indexOfFirst { it.method == "Page.navigate" }
                assertTrue(enableIdx in 0 until navIdx, "Page.enable 必须先于 Page.navigate: $cmds")
                // navigate 帧携带 sessionId（flat 路由身份）
                assertEquals("page-1-session-1", server.receivedCommandsFor("Page.navigate").single().sessionId)
                record("navigate_waits_load", "ok")
            } finally {
                runCatching { control.close() }
            }
        } finally {
            server.stop()
        }
    }

    // ==================== 场景 2：click_real_mouse_events ====================

    private suspend fun clickRealMouseEvents() {
        val server = newScenario()
        try {
            server.succeed("Input.dispatchMouseEvent")
            // ref 解析脚本应答：元素真实布局中心 (15,15)（探针 btn [8,8,20,20] 中心同形）
            server.onMethod("Runtime.evaluate") { params ->
                val expr = (params["expression"] as? JsonPrimitive)?.content.orEmpty()
                if (expr.contains("data-mederi-ref")) evalStringResult("""{"found":true,"cx":15.0,"cy":15.0}""")
                else evalStringResult("null")
            }
            val control = newControl(server)
            try {
                control.start()
                val r = control.click("e1")
                assertTrue(r is OperationResult.Success, "click(e1) 应 Success: $r")
                // 真实鼠标三事件序列：mouseMoved → mousePressed → mouseReleased
                val mice = server.receivedCommandsFor("Input.dispatchMouseEvent")
                assertEquals(
                    listOf("mouseMoved", "mousePressed", "mouseReleased"),
                    mice.map { (it.params["type"] as? JsonPrimitive)?.content },
                    "click 必须是三事件序列: $mice",
                )
                mice.forEach { cmd ->
                    assertEquals(15.0, (cmd.params["x"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull())
                    assertEquals(15.0, (cmd.params["y"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull())
                }
                assertEquals("left", (mice[1].params["button"] as? JsonPrimitive)?.content)
                assertEquals(1, (mice[1].params["clickCount"] as? JsonPrimitive)?.contentOrNull?.toInt())
                // ref 解析走注入属性 querySelector（与 AX 树解耦，执行拍板）
                val evalExpr = (server.receivedCommandsFor("Runtime.evaluate").single().params["expression"] as? JsonPrimitive)
                    ?.content.orEmpty()
                assertTrue(
                    evalExpr.contains("data-mederi-ref") && evalExpr.contains("getBoundingClientRect"),
                    "ref 解析必须 querySelector(data-mederi-ref) + 真实布局: $evalExpr",
                )
                record("click_real_mouse_events", "ok")
            } finally {
                runCatching { control.close() }
            }
        } finally {
            server.stop()
        }
    }

    // ==================== 场景 3：type_key_events ====================

    private suspend fun typeKeyEvents() {
        val server = newScenario()
        try {
            server.succeed("Input.dispatchKeyEvent", "Input.insertText")
            server.onMethod("Runtime.evaluate") { params ->
                val expr = (params["expression"] as? JsonPrimitive)?.content.orEmpty()
                when {
                    expr.contains("el.value=") -> evalStringResult("filled") // locator.type fill 语义
                    expr.contains("el.focus()") -> evalStringResult("focused") // locator.press 聚焦
                    else -> evalStringResult("")
                }
            }
            val control = newControl(server)
            try {
                control.start()
                // ① press(ENTER)：非字符键 → keyDown/keyUp，无 insertText
                assertTrue(control.press(KeyboardKey.ENTER) is OperationResult.Success)
                var keys = server.receivedCommandsFor("Input.dispatchKeyEvent")
                assertEquals(
                    listOf("keyDown", "keyUp"),
                    keys.map { (it.params["type"] as? JsonPrimitive)?.content },
                )
                keys.forEach { assertEquals("Enter", (it.params["key"] as? JsonPrimitive)?.content) }
                assertEquals(0, server.receivedCommandsFor("Input.insertText").size)
                // ② press(A)：字符键 → keyDown(text=a) + Input.insertText + keyUp
                assertTrue(control.press(KeyboardKey.A) is OperationResult.Success)
                keys = server.receivedCommandsFor("Input.dispatchKeyEvent")
                assertEquals(4, keys.size)
                assertEquals("a", (keys[2].params["key"] as? JsonPrimitive)?.content)
                assertEquals("a", (keys[2].params["text"] as? JsonPrimitive)?.content)
                val insert = server.receivedCommandsFor("Input.insertText").single()
                assertEquals("a", (insert.params["text"] as? JsonPrimitive)?.content)
                // ③ locator.type：fill 语义（evaluate 设值 + input/change 事件，无按键流）
                val loc = control.locator("input#x")
                assertTrue(loc.type("hi") is OperationResult.Success)
                val typeExprs = server.receivedCommandsFor("Runtime.evaluate").map {
                    (it.params["expression"] as? JsonPrimitive)?.content.orEmpty()
                }
                assertTrue(
                    typeExprs.any { it.contains("el.value=") && it.contains("dispatchEvent") },
                    "locator.type 必须 evaluate 设值 + 触发事件: $typeExprs",
                )
                // ④ locator.press：元素 focus + 按键序列
                assertTrue(loc.press(KeyboardKey.ENTER) is OperationResult.Success)
                keys = server.receivedCommandsFor("Input.dispatchKeyEvent")
                assertEquals(6, keys.size)
                assertEquals(
                    listOf("keyDown", "keyUp"),
                    keys.takeLast(2).map { (it.params["type"] as? JsonPrimitive)?.content },
                )
                // focus 表达式 = 含 el.focus() 且不含设值（fill 表达式两者都含，须重新取全量）
                val focusExprs = server.receivedCommandsFor("Runtime.evaluate")
                    .map { (it.params["expression"] as? JsonPrimitive)?.content.orEmpty() }
                    .filter { it.contains("el.focus()") && !it.contains("el.value=") }
                assertTrue(focusExprs.isNotEmpty(), "locator.press 必须先发元素 focus 表达式")
                record("type_key_events", "ok")
            } finally {
                runCatching { control.close() }
            }
        } finally {
            server.stop()
        }
    }

    // ==================== 场景 4：scroll_wheel_deltas ====================

    private suspend fun scrollWheelDeltas() {
        val server = newScenario()
        try {
            server.succeed("Input.dispatchMouseEvent")
            // 视口 1280×720（探针默认视口）→ 中心 (640,360)
            server.onMethod("Runtime.evaluate") { evalStringResult("""{"w":1280.0,"h":720.0}""") }
            val control = newControl(server)
            try {
                control.start()
                assertTrue(control.scrollPage(0, 400) is OperationResult.Success)
                val wheel = server.receivedCommandsFor("Input.dispatchMouseEvent").single()
                assertEquals("mouseWheel", (wheel.params["type"] as? JsonPrimitive)?.content)
                assertEquals(0.0, (wheel.params["deltaX"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull())
                assertEquals(400.0, (wheel.params["deltaY"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull())
                assertEquals(640.0, (wheel.params["x"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull())
                assertEquals(360.0, (wheel.params["y"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull())
                record("scroll_wheel_deltas", "ok")
            } finally {
                runCatching { control.close() }
            }
        } finally {
            server.stop()
        }
    }

    // ==================== 场景 5：tabs_via_target ====================

    private suspend fun tabsViaTarget() {
        val server = newScenario()
        try {
            server.onMethod("Target.getTargets") {
                buildJsonObject {
                    put(
                        "targetInfos",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("targetId", "page-1"); put("type", "page"); put("attached", true)
                                },
                                buildJsonObject { put("targetId", "svc-1"); put("type", "service_worker") },
                                buildJsonObject {
                                    put("targetId", "page-2"); put("type", "page"); put("attached", true)
                                },
                            ),
                        ),
                    )
                }
            }
            server.succeed("Target.activateTarget")
            server.onMethod("Target.closeTarget") { buildJsonObject { put("success", true) } }
            val control = newControl(server)
            try {
                control.start()
                assertEquals("page-1", control.currentTargetId)
                // openTab：Target.createTarget（不自动切换——对齐 MCP 版 tab_new 语义）
                server.onMethod("Target.createTarget") { buildJsonObject { put("targetId", "page-2") } }
                assertTrue(control.openTab() is OperationResult.Success)
                assertEquals("page-1", control.currentTargetId, "openTab 不得切换当前 tab")
                // listTabs：Target.getTargets 过滤 type=page
                assertEquals(listOf("page-1", "page-2"), control.listTabs())
                // selectTab：activateTarget + 重新 attach（旧 session 作废）
                assertTrue(control.selectTab("page-2") is OperationResult.Success)
                assertEquals("page-2", control.currentTargetId)
                assertEquals(
                    "page-2",
                    (server.receivedCommandsFor("Target.activateTarget").single().params["targetId"] as? JsonPrimitive)?.content,
                )
                assertEquals(
                    "page-2",
                    (server.receivedCommandsFor("Target.attachToTarget").last().params["targetId"] as? JsonPrimitive)?.content,
                )
                // closeTab：Target.closeTarget
                assertTrue(control.closeTab("page-2") is OperationResult.Success)
                assertEquals(
                    "page-2",
                    (server.receivedCommandsFor("Target.closeTarget").single().params["targetId"] as? JsonPrimitive)?.content,
                )
                record("tabs_via_target", "ok")
            } finally {
                runCatching { control.close() }
            }
        } finally {
            server.stop()
        }
    }

    // ==================== 场景 6：snapshot_ref_format ====================

    private suspend fun snapshotRefFormat() {
        val server = newScenario()
        try {
            server.onMethod("DOM.getDocument") { domDocumentScript() }
            server.onMethod("Accessibility.getFullAXTree") { axTreeScript() }
            // 补充脚本（FakeCdpServer 能力）：Page.captureScreenshot 回 {"data": base64}
            server.onMethod("Page.captureScreenshot") { buildJsonObject { put("data", "aGVsbG8=") } }
            val bodyText = "BodyMarker " + "x".repeat(200)
            server.onMethod("Runtime.evaluate") { params ->
                val expr = (params["expression"] as? JsonPrimitive)?.content.orEmpty()
                when {
                    expr.contains("count:els.length") ->
                        evalStringResult("""{"count":2,"ids":["","b1"]}""") // 注入应答
                    expr.contains("document.title") ->
                        evalStringResult("""{"url":"https://example.com/","title":"Example"}""") // 元数据
                    expr.contains("innerText") ->
                        evalStringResult(bodyText) // 正文
                    else -> evalStringResult("")
                }
            }
            val control = newControl(server)
            try {
                control.start()
                val snap = control.snapshot()
                val lines = snap.yaml.lineSequence().toList()
                // 首两行 URL:/Title: 前缀（BrowserOperator parseSnapshotText 锚点，零漂移）
                assertEquals("URL: https://example.com/", lines.getOrNull(0))
                assertEquals("Title: Example", lines.getOrNull(1))
                // 元素行：- <role> "name" [ref=eN]（AX 树语义：heading 也拿 ref）
                assertTrue(lines.any { it == "- heading \"Hello\" [ref=e1]" }, "缺 heading 行: $snap")
                assertTrue(lines.any { it == "- button \"Go\" [ref=e2]" }, "缺 button 行: $snap")
                assertTrue(
                    lines.drop(2).any { it.matches(Regex("- \\w+ \"[^\"]*\" \\[ref=e\\d+\\]")) },
                    "元素行必须匹配 - role \"name\" [ref=eN]: $snap",
                )
                // 正文在控件行之后（预算尾部）
                assertTrue(snap.yaml.contains("BodyMarker"), "大预算下正文必须保留: $snap")
                // data-mederi-ref 注入 evaluate 被调用（属性注入，页面侧自判可交互 + 顺序编号）
                val injectExpr = server.receivedCommandsFor("Runtime.evaluate")
                    .map { (it.params["expression"] as? JsonPrimitive)?.content.orEmpty() }
                    .first { it.contains("count:els.length") }
                assertTrue(
                    injectExpr.contains("data-mederi-ref") && injectExpr.contains("setAttribute"),
                    "注入脚本必须 setAttribute(data-mederi-ref): $injectExpr",
                )
                // ref 表语义（AX 对齐：role/name；#id relselector）
                assertEquals("Go", control.lastRefIndex?.get("e2")?.name)
                assertEquals("button", control.lastRefIndex?.get("e2")?.role)
                assertEquals("#b1", control.lastRefIndex?.get("e2")?.relselector)
                // screenshot（补充脚本断言：base64 decode 一致）
                val png = control.screenshot()
                assertNotNull(png)
                assertTrue(Arrays.equals(png, "hello".toByteArray()), "base64 解码必须一致")
                // max_chars 小预算：控件行优先保留，正文被截断（正常截断，非错误）
                val small = newControl(server, maxChars = 60)
                try {
                    small.start()
                    val smallSnap = small.snapshot()
                    assertTrue(smallSnap.yaml.startsWith("URL: "), "小预算快照仍必须含 URL/Title 首行")
                    assertFalse(
                        smallSnap.yaml.contains("BodyMarker"),
                        "小预算下正文必须被截断: ${smallSnap.yaml}",
                    )
                } finally {
                    runCatching { small.close() }
                }
                record("snapshot_ref_format", "ok")
            } finally {
                runCatching { control.close() }
            }
        } finally {
            server.stop()
        }
    }

    // ==================== 脚本数据（照 ST1 探针实测形状）====================

    /**
     * DOM.getDocument 应答（depth=-1 全树）：root(#document, node 0) → html(1) → body(2) →
     * h1(3) + button(4, id=b1)。nodeId 与 backendNodeId 同源（探针实测）。
     */
    private fun domDocumentScript(): JsonObject {
        fun elem(
            nodeId: Int,
            nodeType: Int,
            localName: String,
            attrs: List<String> = emptyList(),
            children: List<JsonObject> = emptyList(),
        ): JsonObject = buildJsonObject {
            put("nodeId", nodeId)
            put("backendNodeId", nodeId)
            put("nodeType", nodeType)
            put("localName", localName)
            if (attrs.isNotEmpty()) put("attributes", JsonArray(attrs.map { JsonPrimitive(it) }))
            if (children.isNotEmpty()) put("children", JsonArray(children))
        }
        val h1 = elem(3, 1, "h1")
        val button = elem(4, 1, "button", attrs = listOf("id", "b1"))
        val bodyNode = elem(2, 1, "body", children = listOf(h1, button))
        val html = elem(1, 1, "html", children = listOf(bodyNode))
        return buildJsonObject { put("root", elem(0, 9, "", children = listOf(html))) }
    }

    /** Accessibility.getFullAXTree 应答（探针 shapeSample：role/name 为 {type,value} 包装）。 */
    private fun axTreeScript(): JsonObject {
        fun axNode(backendDOMNodeId: Int, role: String, name: String?): JsonObject = buildJsonObject {
            put("backendDOMNodeId", backendDOMNodeId)
            put("role", buildJsonObject { put("type", "role"); put("value", role) })
            if (name != null) put("name", buildJsonObject { put("type", "string"); put("value", name) })
            put("ignored", false)
        }
        return buildJsonObject {
            put(
                "nodes",
                JsonArray(
                    listOf(
                        axNode(0, "RootWebArea", null),
                        axNode(3, "heading", "Hello"),
                        axNode(4, "button", "Go"),
                    ),
                ),
            )
        }
    }
}
