package xyz.mederi.browser.obscura

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import xyz.mederi.browser.BrowserControl
import xyz.mederi.browser.BrowserLocator
import xyz.mederi.browser.LocatorKind
import xyz.mederi.browser.PageSnapshot
import xyz.mederi.browser.ScreencastFrame
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult
import xyz.mederi.debug.DebugLog
import java.util.Base64

/**
 * Obscura CDP 版 [BrowserControl]（ST3，替代原 MCP 版（ST5 已删除）的新核心；
 * 观测落档 `core/build/obscura-cdp-control-test.json`）。
 *
 * 底座 [ObscuraCdpClient]（ST2：透传命令 + 事件通道，无域语义）；快照层 [ObscuraCdpSnapshot.kt]。
 * 与 MCP 版的语义差异（全部由 CDP 原生能力解决 MCP 实病）：
 * - navigate：`Page.enable`（幂等，保 load 事件流）+ 先订阅 [ObscuraCdpClient.events]
 *   再发 `Page.navigate`（replay=0 竞态防护，ST2 同类教训）；等当前 session 的
 *   `Page.loadEventFired`，**超时不算失败**（页面慢是常态，附 `load_event_not_arrived` detail）；
 *   CdpCommandException 才算失败。MCP 版 navigate = callTool 即返回（obscura 内部等 load）。
 * - 交互全部真实 CDP 输入事件（ST1 探针实测命中）：click = Input.dispatchMouseEvent
 *   mouseMoved→mousePressed→mouseReleased（button=left, clickCount=1, 元素真实布局中心）；
 *   scroll = 真实滚轮（mouseWheel deltaX/deltaY，元素中心/视口中心/指定坐标三入口）；
 *   press = Input.dispatchKeyEvent(keyDown[/insertText]/keyUp，字符键 keyDown+insertText 简化——
 *   ST1 探针实测 via=keydown-seq+insertText 生效）；hover = mouseMoved（**MCP 版不支持，核心补齐**）。
 * - 坐标一律 [Runtime.evaluate] getBoundingClientRect 真实布局（**DOMSnapshot 合成 bounds 禁用**，ST1 实测）；
 *   数值解析走 Double（ST1 实测数值序列化为 0.0 字面量）。
 * - ref 点击解析 = 快照注入的 `data-mederi-ref` 属性 + querySelector（执行拍板：与 AX 树完全解耦，
 *   不依赖 DOM.setAttributeValue 等未实测 CDP 方法）。
 * - tab 族走 Target 域（createTarget/getTargets 过滤 type=page/activateTarget/closeTarget，ST1 实测形状）：
 *   openTab **不自动切换**（对齐 MCP 版 tab_new 语义，切换需显式 selectTab）；
 *   selectTab = activateTarget + 重新 attachToTarget 取新 sessionId（旧 session 作废，attach 幂等可重复）；
 *   closeTab = closeTarget（若关的是当前 tab，当前 session 作废，需 selectTab 恢复）。
 * - 截图 = `Page.captureScreenshot` → base64 → PNG ByteArray（MCP 版走 ImageContent data）。
 * - uploadFile/uploadFileBySelector **不覆盖**（保留契约 default；ST1 实测固定命令行 setFileInputFiles 被禁用）。
 *
 * 生命周期：[start] 幂等（client.start + createTarget(about:blank) + attachToTarget 存 sessionId；
 * close 后可重复调用自愈）；[close] 幂等（client.close 进程回收已封装 + 会话清理）。
 */
class ObscuraCdpControl(
    val client: ObscuraCdpClient,
    /** load 事件等待上界（默认 30s；**超时不判失败**，测试可缩短）。 */
    private val loadWaitMs: Long = NAV_LOAD_WAIT_MS,
    /** 快照 max_chars 预算（默认 20_000，MCP 版对齐；控件行优先、正文尾部截断）。 */
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) : BrowserControl {

    private val layer = "ObscuraCdpControl"
    private var closed = false

    /** 当前 tab（Target 域 targetId；start 时 createTarget，selectTab 时切换）。 */
    internal var currentTargetId: String? = null
        private set

    /** 当前 page 的显式 attach sessionId（ST2 唯一路由身份；selectTab 时重 attach 换新）。 */
    internal var sessionId: String? = null
        private set

    /** 最近一次 [snapshot] 的 ref 表（语义 locator TEXT/ROLE 匹配源；快照前为 null）。 */
    @Volatile
    internal var lastRefIndex: Map<String, RefEntry>? = null

    // ── 生命周期 ──────────────────────────────────────────────

    override suspend fun start() {
        if (currentTargetId != null && sessionId != null && !closed) return // 幂等
        client.start()
        val targetId = client.send(
            "Target.createTarget",
            buildJsonObject { put("url", "about:blank") },
        )["targetId"]?.let { (it as? JsonPrimitive)?.content }
            ?: throw CdpCommandException(code = null, message = "Target.createTarget 未返回 targetId")
        val sid = client.attachToTarget(targetId)
        currentTargetId = targetId
        sessionId = sid
        closed = false
        lastRefIndex = null
        DebugLog.info(layer, "started: target=$targetId session=$sid")
    }

    override suspend fun close() {
        if (closed && client.process == null) return
        client.close() // 幂等：进程回收（SIGTERM→2s→SIGKILL）已封装
        closed = true
        currentTargetId = null
        sessionId = null
        lastRefIndex = null
    }

    /** 会话缺失时自愈 start（首次调用即起浏览器；close 后调用抛明确异常）。 */
    private suspend fun requireSession(): String {
        val s = sessionId
        if (s != null && currentTargetId != null) return s
        if (closed) throw CdpCommandException(
            code = null,
            message = "ObscuraCdpControl 已 close（需重新构造实例再 start）",
        )
        start()
        return sessionId ?: throw CdpCommandException(code = null, message = "start 后仍无 sessionId")
    }

    // ── 页面状态 ──────────────────────────────────────────────

    override suspend fun getCurrentUrl(): String {
        val s = requireSession()
        return evalString("(function(){return location.href})()", s)
    }

    override suspend fun getTitle(): String {
        val s = requireSession()
        return evalString("(function(){return document.title})()", s)
    }

    override suspend fun snapshot(): PageSnapshot {
        return try {
            val snap = captureCdpSnapshot(client, requireSession(), maxChars)
            lastRefIndex = snap.refIndex
            PageSnapshot(snap.url, snap.title, snap.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.error(layer, "snapshot failed", e)
            PageSnapshot("", "", "")
        }
    }

    // ── 导航（事件等待语义：先订阅后发命令；超时不算失败）────────────────────────────────

    override suspend fun navigate(url: String): OperationResult {
        val s = requireSession()
        // 订阅必须建立后再发命令（events replay=0 无订阅者即丢帧；ST2 竞态防护同款处理）
        val load = CompletableDeferred<Unit>()
        val subscribed = CompletableDeferred<Unit>()
        val navScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector = navScope.launch {
            try {
                client.events
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { ev ->
                        if (ev.method == "Page.loadEventFired" &&
                            (ev.sessionId == null || ev.sessionId == s)
                        ) {
                            if (!load.isCompleted) load.complete(Unit)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            }
        }
        try {
            withTimeout(NAV_SUBSCRIBE_MS) { subscribed.await() }
        } catch (e: TimeoutCancellationException) {
            DebugLog.info(layer, "navigate: 事件订阅超时（继续导航，load 等待降级）")
        }
        val result = try {
            client.send("Page.enable", buildJsonObject {}, s) // 幂等：保 load 事件流（ST1 探针同序）
            val nav = client.send(
                "Page.navigate",
                buildJsonObject { put("url", url) },
                s,
            )
            val ok = OperationResult.Success(
                "navigate",
                detail = "url=$url frame=${(nav["frameId"] as? JsonPrimitive)?.contentOrNull}",
            )
            // 事件等待语义（navigate_waits_load）：loadEventFired 到达才放行返回；
            // 超时**不算失败**（页面慢是常态，执行拍板）
            val loaded = withTimeoutOrNull(loadWaitMs) { load.await() } != null
            if (!loaded) {
                DebugLog.info(layer, "navigate: loadEventFired 未在 ${loadWaitMs}ms 内到达（页面慢，不判失败）")
            }
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: CdpCommandException) {
            OperationResult.Failure("navigate", e.message ?: "navigate command failed", recoverable = true)
        }
        collector.cancel()
        return result
    }

    // ── 交互原语消费端（ref 解析 = data-mederi-ref querySelector，执行拍板）──────────────

    override suspend fun click(elementRef: String): OperationResult {
        val s = requireSession()
        return resolveElementCenter(elementRef, s).let {
            if (it == null) {
                OperationResult.Failure("click", "no element for ref $elementRef", recoverable = true)
            } else {
                clickAt(it.first, it.second, s)
            }
        }
    }

    override suspend fun clickByCoordinates(x: Int, y: Int): OperationResult {
        val s = requireSession()
        return clickAt(x.toDouble(), y.toDouble(), s)
    }

    override suspend fun type(elementRef: String, text: String): OperationResult {
        val s = requireSession()
        return fillRef(elementRef, text, s)
    }

    override suspend fun scroll(elementRef: String, deltaX: Int, deltaY: Int): OperationResult {
        val s = requireSession()
        val center = resolveElementCenter(elementRef, s)
            ?: return OperationResult.Failure("scroll", "no element for ref $elementRef", recoverable = true)
        return scrollAt(center.first, center.second, deltaX, deltaY, s)
    }

    /** 覆盖契约 default：视口中心真实滚轮（ST1 探针 mouseWheel 1:1 实测）。 */
    override suspend fun scrollPage(deltaX: Int, deltaY: Int): OperationResult {
        val s = requireSession()
        val view = viewportCenter(s)
        return scrollAt(view.first, view.second, deltaX, deltaY, s)
    }

    /** 覆盖契约 default：指定文档坐标真实滚轮。 */
    override suspend fun scrollByCoordinates(originX: Int, originY: Int, deltaX: Int, deltaY: Int): OperationResult {
        val s = requireSession()
        return scrollAt(originX.toDouble(), originY.toDouble(), deltaX, deltaY, s)
    }

    override suspend fun press(key: KeyboardKey): OperationResult {
        val s = requireSession()
        return pressKeySequence(key, s)
    }

    override suspend fun waitFor(selector: String, timeoutMs: Long): OperationResult {
        val s = requireSession()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val found = try {
                evalString(waitForScript(selector), s)
            } catch (e: CdpCommandException) {
                return OperationResult.Failure("wait_for", e.message ?: "waitFor probe failed", recoverable = false)
            }
            if (found == "1") return OperationResult.Success("wait_for", detail = "selector=$selector")
            if (System.currentTimeMillis() >= deadline) {
                return OperationResult.Failure(
                    "wait_for",
                    "element matching $selector not visible within ${timeoutMs}ms",
                    recoverable = true,
                )
            }
            delay(WAIT_POLL_MS)
        }
    }

    // ── 截图 / JS ────────────────────────────────────────────

    override suspend fun screenshot(): ByteArray? {
        val s = requireSession()
        return try {
            val res = client.send("Page.captureScreenshot", buildJsonObject {}, s)
            val b64 = (res["data"] as? JsonPrimitive)?.contentOrNull ?: return null
            Base64.getDecoder().decode(b64)
        } catch (e: CdpCommandException) {
            DebugLog.error(layer, "screenshot failed", e)
            null
        }
    }

    override suspend fun evaluateJavascript(js: String): String {
        val s = requireSession()
        return evalString(js, s)
    }

    // ── screencast（活动驱动实时帧流，ST4）──────────────────────────────────────

    /**
     * 覆盖契约 default（[BrowserControl.screencastFrames] 空流）：CDP `Page.startScreencast` 活动驱动帧流。
     *
     * 语义（执行拍板）：
     * - **冷流、单活动会话**：每次收集 = 一次 startScreencast，取消即 stopScreencast；不做多收集者
     *   引用计数（KISS 单 viewer）。重复 start 若被 Obscura 报错则记日志继续（帧仍经 events 广播）。
     * - **订阅先于命令**（replay=0 竞态防护，与 [navigate] 同款纪律）：先建 events 过滤收集器
     *   （顶层 sessionId == 当前 page session；帧 params 里的 integer sessionId 是 Ack 句柄，不是路由身份），
     *   onSubscription 确认后再发 startScreencast。
     * - **逐帧 Ack 在发射前**（保证必达：慢消费者不导致 Obscura 停推）；Ack 的 `sessionId` 原样转发
     *   帧 params 的 [JsonPrimitive]（integer 内部句柄，转字符串报 "sessionId must be an integer"，ST1 实测）。
     * - 停止：收集取消（channel closed）或收到 [ObscuraCdpClient.CONNECTION_CLOSED_METHOD] 终结标记时
     *   发 `Page.stopScreencast`（runCatching：连接已断不算错误）；stop 在独立 scope 发出（producer
     *   协程此时已取消，suspend send 无法原地执行）。[close] 不额外挂 stop 钩子（连接关服务端自然清理）。
     */
    override fun screencastFrames(): Flow<ScreencastFrame> = callbackFlow {
        val s = requireSession()
        val subscribed = CompletableDeferred<Unit>()
        // 帧缓冲（CdpEvent 侧，与 producer 的 ScreencastFrame channel 分离；容量对齐 events 的 256）
        val incoming = Channel<CdpEvent>(Channel.BUFFERED)
        val collector = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                client.events
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { ev ->
                        when {
                            ev.method == ObscuraCdpClient.CONNECTION_CLOSED_METHOD -> incoming.close() // 断连 → 流正常完成
                            ev.method == "Page.screencastFrame" && ev.sessionId == s -> incoming.trySend(ev)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            }
        }
        try {
            try {
                withTimeout(NAV_SUBSCRIBE_MS) { subscribed.await() }
            } catch (e: TimeoutCancellationException) {
                DebugLog.info(layer, "screencast: 事件订阅超时（继续，早期帧可能丢失）")
            }
            try {
                client.send("Page.startScreencast", buildJsonObject {}, s)
            } catch (e: CdpCommandException) {
                // 第二个收集者重复 start 可能报错（拍板：捕获继续，帧仍到 SharedFlow 广播）
                DebugLog.info(layer, "screencast: startScreencast 命令失败（继续收帧）: ${e.message}")
            }
            while (true) {
                val ev = try {
                    incoming.receive()
                } catch (e: CancellationException) {
                    throw e // 收集取消 → 透传
                } catch (e: ClosedReceiveChannelException) {
                    break // 断连终结标记 / 关闭 → 正常完成
                }
                val params = ev.params
                val handle = params["sessionId"] // integer 内部句柄（ST1 实测 =1），只用于 Ack
                val b64 = (params["data"] as? JsonPrimitive)?.contentOrNull ?: continue
                val bytes = Base64.getDecoder().decode(b64)
                val meta = (params["metadata"] as? JsonObject)
                    ?.mapValues { entry ->
                        val v = entry.value
                        if (v is JsonPrimitive) v.contentOrNull.orEmpty() else v.toString()
                    }
                    ?: emptyMap()
                // Ack 在发射前（拍板）：params.sessionId 原样 JsonPrimitive 转发（禁止取 content 重构）
                val handleElem = handle as? JsonElement
                if (handleElem != null) {
                    runCatching {
                        client.send(
                            "Page.screencastFrameAck",
                            buildJsonObject { put("sessionId", handleElem) },
                            s,
                        )
                    }.onFailure { DebugLog.error(layer, "screencastFrameAck 失败（继续）", it) }
                }
                channel.send(
                    ScreencastFrame(
                        data = bytes,
                        width = meta["deviceWidth"]?.toDoubleOrNull()?.toInt() ?: 0,
                        height = meta["deviceHeight"]?.toDoubleOrNull()?.toInt() ?: 0,
                        metadata = meta,
                    ),
                )
            }
        } finally {
            collector.cancel()
            incoming.close()
            // stopScreencast 在独立 scope 发出（producer 已取消，原地 suspend send 会立即抛 CancellationException）
            CoroutineScope(Dispatchers.Default).launch {
                runCatching { client.send("Page.stopScreencast", buildJsonObject {}, s) }
            }
        }
    }

    // ── tab 族（Target 域；openTab 不自动切换——对齐 MCP 版 tab_new 语义）──────────────

    override suspend fun openTab(): OperationResult {
        val targetId = try {
            client.send(
                "Target.createTarget",
                buildJsonObject { put("url", "about:blank") },
            )["targetId"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        } catch (e: CdpCommandException) {
            return OperationResult.Failure("open_tab", e.message ?: "createTarget failed", recoverable = true)
        }
        // 不自动切换（对齐 MCP 版 tab_new 语义：仅开 tab，焦点/会话不动）——
        // 需切换时显式 selectTab(targetId)（activateTarget + 重 attach）
        return OperationResult.Success("open_tab", detail = "target=$targetId")
    }

    override suspend fun closeTab(tabId: String): OperationResult {
        return try {
            val res = client.send(
                "Target.closeTarget",
                buildJsonObject { put("targetId", tabId) },
            )
            if (tabId == currentTargetId) {
                // 关的是当前 tab：session 作废；requireSession 会自愈起新 about:blank tab
                sessionId = null
                currentTargetId = null
                lastRefIndex = null
            }
            val ok = (res["success"] as? JsonPrimitive)?.contentOrNull == "true"
            if (ok) OperationResult.Success("close_tab", detail = "tab=$tabId")
            else OperationResult.Failure("close_tab", "closeTarget success=false for $tabId", recoverable = true)
        } catch (e: CdpCommandException) {
            OperationResult.Failure("close_tab", e.message ?: "closeTarget failed", recoverable = true)
        }
    }

    override suspend fun listTabs(): List<String> {
        return try {
            val res = client.send("Target.getTargets", buildJsonObject {})
            val ids = mutableListOf<String>()
            (res["targetInfos"] as? JsonArray)?.forEach { info ->
                val obj = info.jsonObject
                if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "page") {
                    (obj["targetId"] as? JsonPrimitive)?.contentOrNull?.let(ids::add)
                }
            }
            ids
        } catch (e: CdpCommandException) {
            DebugLog.error(layer, "listTabs failed", e)
            emptyList()
        }
    }

    override suspend fun selectTab(tabId: String): OperationResult {
        return try {
            client.send(
                "Target.activateTarget",
                buildJsonObject { put("targetId", tabId) },
            )
            val sid = client.attachToTarget(tabId) // 旧 session 作废，attach 幂等可重复（执行拍板）
            currentTargetId = tabId
            sessionId = sid
            lastRefIndex = null // 切 tab 后 ref 失效（快照语义：ref 只在当前快照有效）
            OperationResult.Success("select_tab", detail = "tab=$tabId session=$sid")
        } catch (e: CdpCommandException) {
            OperationResult.Failure("select_tab", e.message ?: "activate/attach failed", recoverable = true)
        }
    }

    // ── locator（CSS/XPATH 直传；语义 kind 走最近快照 refIndex 匹配，照 MCP 版分支）──────

    override suspend fun locator(selector: String): BrowserLocator =
        ObscuraCdpLocator(this, selector, ObscuraCdpLocator.Mode.CSS)

    override suspend fun locator(
        kind: LocatorKind,
        value: String,
        name: String?,
        exact: Boolean,
    ): BrowserLocator {
        return when (kind) {
            LocatorKind.CSS -> locator(value)
            LocatorKind.XPATH -> ObscuraCdpLocator(this, value, ObscuraCdpLocator.Mode.XPATH)
            LocatorKind.PLACEHOLDER -> locator("[placeholder=\"${escapeAttr(value)}\"]")
            LocatorKind.TEST_ID -> locator("[data-testid=\"${escapeAttr(value)}\"]")
            LocatorKind.ALT_TEXT -> locator("[alt=\"${escapeAttr(value)}\"]")
            LocatorKind.TITLE -> locator("[title=\"${escapeAttr(value)}\"]")
            LocatorKind.LABEL -> locator("[aria-label=\"${escapeAttr(value)}\"]")
            LocatorKind.TEXT, LocatorKind.ROLE -> {
                val ref = findRefBySemantic(value, name, exact, kind)
                if (ref == null) MissingLocator(kind.name, value)
                else ObscuraCdpLocator(this, "[data-mederi-ref=\"$ref\"]", ObscuraCdpLocator.Mode.CSS)
            }
        }
    }

    /** TEXT/ROLE：在 [lastRefIndex]（最近快照 ref 表）中按语义匹配 → ref（照 MCP 版 findRefByText 语义）。 */
    private fun findRefBySemantic(value: String, name: String?, exact: Boolean, kind: LocatorKind): String? {
        val index = lastRefIndex ?: return null // 快照未取过：无从匹配（MissingLocator）
        val needle = name ?: value
        for ((ref, entry) in index) {
            val matches = if (kind == LocatorKind.ROLE) {
                entry.role.equals(value, ignoreCase = true) &&
                    (name == null || entry.name == name || entry.name.contains(name))
            } else {
                if (exact) entry.name == needle else entry.name.contains(needle)
            }
            if (matches) return ref
        }
        return null
    }

    /** 定位器会话入口（Locator 复用原语需会话；无会话时自愈 [start]，close 后抛明确异常）。 */
    internal suspend fun ensureSession(): String = requireSession()

    // ── uploadFile / uploadFileBySelector：不覆盖（契约 default UnsupportedOperationException）──────
    // ST1 实测：固定命令行（无 --allow-file-access）下 DOM.setFileInputFiles 被禁用。

    // ==================== 内部原语（ObscuraCdpLocator 复用；internal 同包可见）====================

    /** ref → 元素真实布局中心（document.querySelector('[data-mederi-ref]') + getBoundingClientRect + 滚入视口）。 */
    internal suspend fun resolveElementCenter(ref: String, s: String): Pair<Double, Double>? {
        val sel = "[data-mederi-ref=\"${escapeAttr(ref)}\"]"
        val raw = evalString(elementCenterScript(sel), s)
        if (raw.isEmpty() || raw == "null") return null
        val rect = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return null
        if ((rect["found"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val cx = (rect["cx"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
        val cy = (rect["cy"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
        return cx to cy
    }

    /** 元素中心真实滚轮（wheel 原语，[ObscuraCdpScripts.kt] 的 [cdpMouseWheel]；ST1 实测 1:1 滚动）。 */
    internal suspend fun scrollAt(x: Double, y: Double, deltaX: Int, deltaY: Int, s: String): OperationResult =
        cdpMouseWheel(client, x, y, deltaX, deltaY, s)

    /** 真实鼠标点击（[cdpMouseClick]：mouseMoved→mousePressed→mouseReleased，ST1 探针实测命中）。 */
    internal suspend fun clickAt(x: Double, y: Double, s: String): OperationResult =
        cdpMouseClick(client, x, y, s)

    /** 悬停：mouseMoved 到坐标（MCP 版缺失的核心补齐，[cdpHover]）。 */
    internal suspend fun hoverAt(x: Double, y: Double, s: String): OperationResult =
        cdpHover(client, x, y, s)

    /** 按键序列：keyDown[/insertText]/keyUp（[cdpKeyPress]，ST1 探针 keyDown-seq+insertText 生效实测）。 */
    internal suspend fun pressKeySequence(key: KeyboardKey, s: String): OperationResult =
        cdpKeyPress(client, key, s)

    /** 视口中心（scrollPage 原点；evaluate 真实布局，innerWidth/innerHeight 数值走 Double）。 */
    internal suspend fun viewportCenter(s: String): Pair<Double, Double> {
        val raw = evalString(
            "(function(){return JSON.stringify({w:window.innerWidth,h:window.innerHeight})})()",
            s,
        )
        val obj = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrNull()
        val w = (obj?.get("w") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val h = (obj?.get("h") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
        return (w / 2.0) to (h / 2.0)
    }

    /**
     * [Runtime.evaluate] returnByValue 字符串化（执行拍板）：字符串取 value；number 走 double；
     * null/undefined 空；对象取 JSON 原文；非对象 result 走 ST2 `_raw_non_object_result` 语义。
     */
    internal suspend fun evalString(expression: String, s: String): String {
        val res = client.send(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("returnByValue", true)
            },
            s,
        )
        val result = res["result"]?.jsonObject ?: return ""
        if (result["exceptionDetails"] != null) return ""
        val value = result["value"] ?: return ""
        return when (value) {
            is JsonPrimitive -> when {
                value.isString -> value.content
                value is kotlinx.serialization.json.JsonNull -> ""
                else -> value.content?.toDoubleOrNull()?.toString() ?: value.content.orEmpty()
            }
            else -> value.toString() // 对象/数组（returnByValue 直给）
        }
    }

    /** fill 原语：ref 元素设值 + input/change 事件（MCP 版 fill 触发 input+change，保持）。 */
    internal suspend fun fillRef(ref: String, text: String, s: String): OperationResult =
        cdpGuard("type") {
            val sel = "[data-mederi-ref=\"${escapeAttr(ref)}\"]"
            val out = evalString(fillScript(sel, text), s)
            if (out.isEmpty()) OperationResult.Failure("type", "no element for ref $ref", recoverable = true)
            else OperationResult.Success("type", detail = out)
        }

    internal companion object {
        /** 事件订阅上界（订阅建立晚于命令发出会丢帧——replay=0 竞态防护）。 */
        const val NAV_SUBSCRIBE_MS = 5_000L

        /** load 等待上界：超时**不判失败**（页面慢是常态，ST2/执行拍板语义）。 */
        const val NAV_LOAD_WAIT_MS = 30_000L

        /** 快照默认 max_chars（MCP 版 20000 预算对齐）。 */
        const val DEFAULT_MAX_CHARS = 20_000

        /** waitFor 轮询间隔（执行拍板 100ms）。 */
        const val WAIT_POLL_MS = 100L
    }
}
