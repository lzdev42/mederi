package xyz.mederi.browser

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import xyz.mederi.debug.DebugLog

/**
 * Obscura CDP 真二进制探针（ST1，采集工具而非回归测试）：用 release 里的真 obscura 子进程
 * （`serve --port 0 --stealth --allow-private-network --ready-file`）+ 裸 Ktor WS 客户端
 * （不依赖任何生产模块，ObscuraCdpClient 尚不存在）实测方法矩阵，逐项落档
 * `core/build/obscura-cdp-probe.json`（kotlinx.serialization 构造，禁止手拼 JSON 字符串）。
 *
 * 语义（与 ST2 探针相反）：五项硬键 probe_navigate / probe_input_click / probe_wheel_scroll /
 * probe_screencast_frame / probe_real_geometry 的 ok/fail 由 Obscura 真实行为决定，如实落档，
 * 不放宽判定造假 ok。测试本身只在基础设施故障时 fail：
 * - 二进制找不到 → assumeTrue 跳过
 * - 进程起不来 / ready-file 不出现 / WS 连不上 / attach 拿不到 sessionId → 记档后 throw
 * 方法级 fail（如 Input 不命中、setFileInputFiles 被禁用）→ 落档 fail + error 原文，测试不 throw。
 *
 * 已核实的先验事实（2026-11-16 本机 node 冒烟）：
 * - ready-file 是 JSON：`{"pid":..,"host":"127.0.0.1","port":..,"websocket_path":"/devtools/browser"}`；
 *   端点 = ws://host:port + websocket_path。
 * - data:URL 导航可用（loadEventFired 到达）；`--workers 1` 时 serve 是单进程无子进程，
 *   destroy 主进程即回收（SIGTERM 2s → SIGKILL 双保险）。
 *
 * 首跑实测结论（2026-11-16，见 obscura-cdp-probe.json 各 *_detail）：
 * - 五项硬键全 ok：data:URL 导航可用（url_scheme=data）；btn 中心真实事件点击使
 *   window.__c 0→1；mouseWheel(deltaY=400) 使 scrollY 0→400；screencast 活动驱动出帧
 *   （startScreencast 应答含 obscuraFrameSource=activity-driven，帧 data 为 PNG base64 ~26KB）；
 *   getBoundingClientRect 给出真实几何（btn [8,8,20,20] vs DOMSnapshot 合成 1280×18 均匀堆叠）。
 * - 数值类 CDP value 序列化为带小数点字面量（`0.0`/`1.0`）——数值提取要走 toDouble，别用 toInt 直解。
 * - Page.screencastFrameAck 的 sessionId 参数必须原样转发帧参数里的（integer）句柄，转字符串会
 *   触发 "Invalid parameters: sessionId must be an integer"。
 * - DOM.setFileInputFiles 在固定命令行（无 --allow-file-access）下被禁用（-32601，原文见
 *   probe_setfileinputfiles_detail）；带 --allow-file-access 重启后同调用成功（files.length=1）。
 */
class ObscuraCdpProbeTest {

    private val probeFile = File("build/obscura-cdp-probe.json")
    private val observations = linkedMapOf<String, String>()
    private val probeErrors = linkedMapOf<String, String>()
    private val json = Json { prettyPrint = true }

    private var lastProcess: Process? = null

    // ==================== 观测落档（照 ObscuraMcpProbeTest 的 record/persist 模式）====================

    private fun persist() {
        probeFile.parentFile?.mkdirs()
        val obj = JsonObject(observations.mapValues { (_, v) -> JsonPrimitive(v) })
        probeFile.writeText(json.encodeToString(JsonObject.serializer(), obj))
    }

    private fun record(key: String, value: String) {
        observations[key] = value
        persist()
    }

    private fun noteError(method: String, raw: String) {
        probeErrors[method] = raw
        DebugLog.error("ObscuraCdpProbe", "CDP $method error: $raw")
    }

    @AfterTest
    fun after() {
        // finally 已回收；这里是双保险（destroy 幂等）
        runCatching { lastProcess?.destroy() }
    }

    // ==================== 测试主体 ====================

    @Test
    fun `probe obscura cdp method matrix`() = runBlocking {
        // ---- 定位二进制（≤6 级向上找 release/Mederi.app，找不到整类跳过）----
        val binary = locateObscura()
        assumeTrue("obscura binary not found under release/Mederi.app/Contents/Resources", binary != null)
        val binaryPath: String = binary!!
        record("probe_infra", "binary=$binaryPath")

        var handle = startObscura(binaryPath, emptyList())
        record("probe_ready_file", "ok")
        record("ready_file_content", handle.readyContent)
        record("ws_endpoint", handle.wsEndpoint)

        var conn = openConnection(handle)
        var page = attachTarget(conn)

        // ---- 3. probe_navigate：data:URL；失败则 --allow-file-access + file:// 重试（fallback 规则）----
        val uploadFile = File.createTempFile("obscura-cdp-probe-upload", ".txt").apply {
            writeText("upload-test")
            deleteOnExit()
        }
        var navigated = false
        var urlScheme = "data"
        val dataUrl = "data:text/html;charset=utf-8," + URLEncoder.encode(pageHtml, Charsets.UTF_8).replace("+", "%20")
        if (tryNavigate(conn, page.sessionId, dataUrl, "probe_navigate")) {
            navigated = true
        } else {
            // data:URL 不可用（如实已落档 probe_navigate=fail）→ file:// + --allow-file-access 重启
            record("probe_navigate_data_attempt", "fail")
            record("probe_url_scheme_fallback", "data-navigate-failed->file")
            conn.close()
            val midReap = handle.close()
            DebugLog.info("ObscuraCdpProbe", "intermediate process reap: $midReap")
            handle = startObscura(binaryPath, listOf("--allow-file-access"))
            record("probe_ready_file_fallback", "ok")
            record("ready_file_content_fallback", handle.readyContent)
            conn = openConnection(handle)
            page = attachTarget(conn)
            val pageFile = File.createTempFile("obscura-cdp-probe-page", ".html").apply {
                writeText(pageHtml)
                deleteOnExit()
            }
            urlScheme = "file"
            val fileUrl = "file://" + pageFile.absolutePath
            navigated = tryNavigate(conn, page.sessionId, fileUrl, "probe_navigate")
        }
        record("probe_url_scheme", urlScheme)

        if (navigated) {
            // ---- 4~14. 页面级方法矩阵（顺序执行，单连接复用）----
            probeRealGeometry(conn, page.sessionId)
            probeInputClick(conn, page.sessionId)
            probeKeyEvents(conn, page.sessionId)
            probeWheelScroll(conn, page.sessionId)
            probeScreencastFrame(conn, page.sessionId)
            probeTabs(conn)
            probeGetFullAXTree(conn, page.sessionId)
            probeDomQuery(conn, page.sessionId)
            probeEmulation(conn, page.sessionId)
            probeSetFileInputFiles(conn, page.sessionId, uploadFile)
            probeHover(conn, page.sessionId)
        } else {
            // 页面根本进不去：剩余探针无从谈起，如实落档
            record("probe_real_geometry", "fail:no-page")
            record("probe_input_click", "fail:no-page")
            record("probe_wheel_scroll", "fail:no-page")
            record("probe_screencast_frame", "fail:no-page")
        }

        // ---- 15. 附加：全部方法 error 原文聚合 ----
        record("probe_errors", JsonObject(probeErrors.mapValues { (_, v) -> JsonPrimitive(v) }).toString())

        // ---- 收尾：关连接 + 回收进程（无孤儿）----
        conn.close()
        val reap = handle.close()
        record("probe_process_reap", reap)
        if (reap != "ok") {
            throw AssertionError("obscura process not reaped: $reap")
        }
    }

    // ==================== 二进制定位（复用 ObscuraMcpProbeTest 模式，路径修正为 Mederi.app）====================

    private fun locateObscura(): String? {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(7) {
            val d = dir ?: return null
            val candidate = File(d, "release/Mederi.app/Contents/Resources/obscura")
            if (candidate.isFile && candidate.canExecute()) return candidate.canonicalPath
            dir = d.parentFile
        }
        return null
    }

    // ==================== obscura 进程与 ready-file ====================

    /** obscura serve 句柄：主进程 + ready-file 原文 + 解析出的 WS 端点 + serve 日志。 */
    private class ObscuraHandle(
        val process: Process,
        val readyContent: String,
        val wsEndpoint: String,
        val logFile: File,
        private val readyFile: File,
    ) {
        /** 销毁进程（SIGTERM → 2s 宽限 → SIGKILL），返回 "ok"/"fail:still-alive"；清理临时文件。 */
        fun close(): String {
            val p = process
            p.destroy()
            var exited = try {
                p.waitFor(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                p.isAlive
            }
            if (!exited) {
                p.destroyForcibly()
                exited = try {
                    p.waitFor(2, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            }
            runCatching { readyFile.delete() }
            runCatching { logFile.delete() }
            return if (exited) "ok" else "fail:still-alive"
        }
    }

    /** 起 `serve --port 0 --stealth --allow-private-network --ready-file <tmp>`，等 ready-file 落盘并解析端点。 */
    private fun startObscura(binary: String, extraFlags: List<String>): ObscuraHandle {
        val readyFile = Files.createTempFile("obscura-cdp-probe-ready", ".json").toFile()
        val logFile = Files.createTempFile("obscura-cdp-probe-serve", ".log").toFile()
        val command = listOf(
            binary, "serve", "--port", "0", "--stealth", "--allow-private-network",
            "--ready-file", readyFile.absolutePath,
        ) + extraFlags
        record("probe_command", command.drop(1).joinToString(" "))
        val process = ProcessBuilder(command)
            .redirectOutput(logFile)
            .redirectErrorStream(true)
            .start()
        lastProcess = process

        val deadline = System.currentTimeMillis() + 20_000
        var ready = ""
        while (System.currentTimeMillis() < deadline) {
            if (readyFile.length() > 0) {
                ready = readyFile.readText().trim()
                break
            }
            if (!process.isAlive) break
            Thread.sleep(200)
        }
        if (ready.isEmpty()) {
            val logTail = runCatching { logFile.readText().takeLast(500) }.getOrDefault("")
            record("probe_ready_file", "fail:no-ready-file; log=${logTail.take(300)}")
            record("ready_file_content", "")
            val reap = process.destroy().let {
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }.isSuccess
            }
            if (!reap) process.destroyForcibly()
            record("probe_process_reap", if (process.isAlive) "fail:still-alive" else "ok")
            throw AssertionError("obscura serve did not publish ready file within 20s: $logTail")
        }
        return ObscuraHandle(process, ready, parseWsEndpoint(ready), logFile, readyFile)
    }

    /**
     * ready-file 端点解析：实测格式是 JSON {pid,host,port,websocket_path}（原样记录见 ready_file_content）；
     * 解析失败时按 spec 回退：内容以 ws:// 开头则原样用，否则拼 ws:// + 内容。
     */
    private fun parseWsEndpoint(ready: String): String {
        val parsed = runCatching {
            val obj = json.parseToJsonElement(ready).jsonObject
            val host = (obj["host"] as? JsonPrimitive)?.content ?: "127.0.0.1"
            val port = (obj["port"] as? JsonPrimitive)?.content ?: error("ready-file missing port")
            val path = (obj["websocket_path"] as? JsonPrimitive)?.content ?: "/devtools/browser"
            "ws://$host:$port$path"
        }
        return parsed.getOrNull() ?: (if (ready.startsWith("ws://")) ready else "ws://$ready")
    }

    // ==================== 裸 WS CDP 连接（自持 id 配对 + 事件收集，不依赖生产模块）====================

    private class CdpError(val method: String, val raw: String) : RuntimeException("CDP $method: $raw")

    /** CDP 事件（method + 所属 CDP session + params + 单调递增序号；序号用于"新事件"锚点过滤）。 */
    private data class CdpEvent(
        val method: String,
        val sessionId: String?,
        val params: JsonObject,
        val seq: Int,
    )

    /** Runtime.evaluate 结果：value 为 returnByValue 缺失时的原始值（未开 returnByValue，字符串/数字直接给值）。 */
    private data class EvalResult(val value: JsonElement?, val exception: JsonElement?, val raw: String)

    private class CdpConnection(
        val client: HttpClient,
        val session: DefaultClientWebSocketSession,
        json: Json,
    ) {
        private val jsonRef = json
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
        private val nextId = AtomicInteger(1)
        private val seqCounter = AtomicInteger(0)
        private val events = Channel<CdpEvent>(Channel.UNLIMITED)
        val readerJob: Job

        init {
            readerJob = session.launch {
                try {
                    while (true) {
                        val frame = session.incoming.receive()
                        when (frame) {
                            is Frame.Text -> {
                                val msg = runCatching { jsonRef.parseToJsonElement(String(frame.data, Charsets.UTF_8)) }.getOrNull() ?: continue
                                val obj = msg.jsonObject
                                val id = (obj["id"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
                                if (id != null) {
                                    pending.remove(id)?.complete(obj)
                                } else if (obj["method"] != null) {
                                    val ev = CdpEvent(
                                        method = (obj["method"] as? JsonPrimitive)?.content ?: "",
                                        sessionId = (obj["sessionId"] as? JsonPrimitive)?.contentOrNull,
                                        params = obj["params"]?.jsonObject ?: buildJsonObject {},
                                        seq = seqCounter.incrementAndGet(),
                                    )
                                    events.trySend(ev)
                                }
                            }
                            is Frame.Ping -> runCatching { session.outgoing.send(Frame.Pong(frame.data)) }
                            is Frame.Close -> break
                            else -> Unit
                        }
                    }
                } finally {
                    events.close()
                }
            }
        }

        /** 当前事件序号（"触发请求前"的锚点：只认 seq 更大的新事件，过滤掉历史同名事件）。 */
        fun seqNow(): Int = seqCounter.get()

        /** 发一条 CDP 请求并等配对应答（整体消息 {"id","result"} 或 {"id","error"}）。15s 上界防挂死。 */
        suspend fun request(method: String, params: JsonObject = buildJsonObject {}, sessionId: String? = null): JsonElement {
            val myId = nextId.getAndIncrement()
            val msg = buildJsonObject {
                put("id", JsonPrimitive(myId))
                put("method", method)
                put("params", params)
                if (sessionId != null) put("sessionId", JsonPrimitive(sessionId))
            }
            val deferred = CompletableDeferred<JsonElement>()
            pending[myId] = deferred
            try {
                session.outgoing.send(Frame.Text(msg.toString()))
                return withTimeout(15_000) { deferred.await() }
            } finally {
                pending.remove(myId)
            }
        }

        /** [request] 的 result 版；带 error 应答时抛 [CdpError]（原文保留）。 */
        suspend fun requestResult(method: String, params: JsonObject = buildJsonObject {}, sessionId: String? = null): JsonObject {
            val obj = request(method, params, sessionId).jsonObject
            val err = obj["error"]
            if (err != null) throw CdpError(method, err.toString())
            return when (val r = obj["result"]) {
                null -> buildJsonObject {}
                is JsonObject -> r
                else -> buildJsonObject { put("_raw_non_object_result", r) }
            }
        }

        suspend fun evaluate(expression: String, sessionId: String?): EvalResult {
            val r = requestResult(
                "Runtime.evaluate",
                buildJsonObject { put("expression", expression) },
                sessionId,
            )
            val inner = r["result"]?.jsonObject
            return EvalResult(
                value = inner?.get("value")?.takeIf { it != JsonNull },
                exception = inner?.get("exceptionDetails"),
                raw = r.toString(),
            )
        }

        /**
         * 等 seq 大于 [minSeq] 的 [method] 事件（过滤历史同名事件）；[timeoutMs] 上界。
         * 等待期间消费的非目标事件被丢弃（探针流程严格顺序，后续等待用各自新的锚点）。
         */
        suspend fun waitForEvent(method: String, minSeq: Int, timeoutMs: Long): CdpEvent? {
            val deadline = System.currentTimeMillis() + timeoutMs
            try {
                while (System.currentTimeMillis() < deadline) {
                    val remaining = deadline - System.currentTimeMillis()
                    // 单事件等待有上界；超时/通道关闭返回 null（探针不挂死）
                    val ev = withTimeoutOrNull(remaining) { events.receive() } ?: return null
                    if (ev.seq > minSeq && ev.method == method) return ev
                }
                return null
            } catch (e: ClosedReceiveChannelException) {
                return null
            }
        }

        fun close() {
            runCatching { readerJob.cancel() }
            runCatching { session.cancel() }
            runCatching { client.close() }
        }
    }

    private suspend fun openConnection(handle: ObscuraHandle): CdpConnection {
        val client = HttpClient(CIO) {
            WebSockets {
                // 探针全程 <2min，把 ping 间隔拉到 1h 避免对未知 CDP 服务器施加 ping 压力
                pingIntervalMillis = 3_600_000L
            }
        }
        val session = try {
            client.webSocketSession(handle.wsEndpoint)
        } catch (e: Exception) {
            runCatching { client.close() }
            record("probe_ws_connect", "fail:${e.message ?: e.javaClass.simpleName}")
            DebugLog.error("ObscuraCdpProbe", "CDP ws connect failed", e)
            throw AssertionError("CDP websocket connect to ${handle.wsEndpoint} failed: ${e.message}", e)
        }
        record("probe_ws_connect", "ok")
        return CdpConnection(client, session, json)
    }

    private data class PageSession(val targetId: String, val sessionId: String)

    /** 2. probe_attach_flatten：createTarget + attachToTarget(flatten=true)，落档 sessionId 精确形状。 */
    private suspend fun attachTarget(conn: CdpConnection): PageSession {
        val anchor = conn.seqNow()
        val created = conn.requestResult(
            "Target.createTarget",
            buildJsonObject { put("url", "about:blank") },
        )
        val targetId = (created["targetId"] as? JsonPrimitive)?.content ?: ""
        val autoAttach = conn.waitForEvent("Target.attachedToTarget", anchor, 2_000)
        val attached = conn.requestResult(
            "Target.attachToTarget",
            buildJsonObject {
                put("targetId", targetId)
                put("flatten", true)
            },
        )
        val sessionId = (attached["sessionId"] as? JsonPrimitive)?.content
        val ok = !sessionId.isNullOrEmpty()
        record("probe_attach_flatten", if (ok) "ok" else "fail:no-sessionId-field")
        record(
            "probe_attach_flatten_detail",
            "createTarget=${trunc(created.toString())} | attachResponse=${trunc(attached.toString())} | " +
                "autoAttachEvent=${trunc(autoAttach?.params?.toString() ?: "<none-observed>", 200)}",
        )
        if (!ok) throw AssertionError("attachToTarget returned no sessionId: $attached")
        return PageSession(targetId, sessionId)
    }

    /** 3. probe_navigate：Page.enable → Page.navigate → loadEventFired（10s 上界）。 */
    private suspend fun tryNavigate(conn: CdpConnection, sessionId: String, url: String, key: String): Boolean {
        val anchor = conn.seqNow()
        return try {
            conn.requestResult("Page.enable", buildJsonObject {}, sessionId)
            val nav = conn.requestResult("Page.navigate", buildJsonObject { put("url", url) }, sessionId)
            val loadEv = conn.waitForEvent("Page.loadEventFired", anchor, 10_000)
            val ok = loadEv != null
            record(key, if (ok) "ok" else "fail:loadEventFired-not-arrived-within-10s")
            record(
                "${key}_detail",
                "navigate=${trunc(nav.toString())} | loadEventFired=${trunc(loadEv?.params?.toString() ?: "<none>", 200)} | " +
                    "url=${url.take(60)}...len=${url.length}",
            )
            if (!ok) noteError("Page.navigate", "loadEventFired not arrived within 10s")
            ok
        } catch (e: CdpError) {
            noteError("Page.navigate", e.raw)
            record(key, "fail:${trunc(e.raw, 300)}")
            record("${key}_detail", "error=${e.raw}")
            false
        }
    }

    /** 4. probe_real_geometry：evaluate getBoundingClientRect（真实布局）vs DOMSnapshot（合成几何）对照。 */
    private suspend fun probeRealGeometry(conn: CdpConnection, sessionId: String) {
        try {
            val btnText = conn.evaluate("JSON.stringify(document.getElementById('btn').getBoundingClientRect())", sessionId)
            val inpText = conn.evaluate("JSON.stringify(document.getElementById('inp').getBoundingClientRect())", sessionId)
            val btnRect = parseRect(btnText.value?.stringValue())
            val inpRect = parseRect(inpText.value?.stringValue())
            if (btnRect == null || inpRect == null) {
                record(
                    "probe_real_geometry",
                    "fail:rect-missing(btn=${btnText.value?.stringValue()?.take(120)} inp=${inpText.value?.stringValue()?.take(120)})",
                )
                noteError("probe_real_geometry", "getBoundingClientRect not parseable: btn=$btnRect inp=$inpRect")
                return
            }
            val b = btnRect
            val i = inpRect
            // 对照：DOMSnapshot 的同节点 bounds（源码自认合成"简单垂直堆叠"）
            val snap = runCatching {
                conn.requestResult(
                    "DOMSnapshot.captureSnapshot",
                    buildJsonObject {
                        put("computedStyles", JsonArray(emptyList()))
                        put("includeDOMRects", true)
                        put("includeBlamingCSS", false)
                    },
                    sessionId,
                )
            }.getOrNull()
            val doc0 = snap?.get("documents")?.jsonArray?.firstOrNull()?.jsonObject
            val snapKeys = doc0?.keys?.toList()?.toString()
            val boundsSample = doc0?.get("layout")?.jsonObject?.get("bounds")?.jsonArray?.take(4)
            val nodeKeys = doc0?.get("nodes")?.jsonObject?.keys?.toList()
            val nonZero = b[2] > 0 && b[3] > 0 && i[2] > 0 && i[3] > 0
            val distinct = b != i
            val synthetic = looksLikeSyntheticStack(b, i)
            val ok = nonZero && distinct && !synthetic
            record("probe_real_geometry", if (ok) "ok" else "fail:rect-synthetic-or-identical")
            record(
                "probe_real_geometry_detail",
                "btnRect=$b | inpRect=$i | nonZero=$nonZero | distinct=$distinct | syntheticLike=$synthetic | " +
                    "domsnapshotDocKeys=$snapKeys | domsnapshotLayoutBoundsSample=${trunc(boundsSample?.toString() ?: "<absent>", 200)} | " +
                    "domsnapshotNodeKeys=$nodeKeys | btnRaw=${trunc(btnText.raw, 200)} | inpRaw=${trunc(inpText.raw, 200)}",
            )
        } catch (e: CdpError) {
            noteError("probe_real_geometry", e.raw)
            record("probe_real_geometry", "fail:${trunc(e.raw, 300)}")
        }
    }

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull

    /** 解析 JSON.stringify 的 rect：[x, y, width, height]。 */
    private fun parseRect(text: String?): List<Double>? {
        val el = text?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return null
        val obj = el as? JsonObject ?: return null
        val raw = listOf("x", "y", "width", "height")
            .map { (obj[it] as? JsonPrimitive)?.content?.toDoubleOrNull() }
        return raw.takeIf { it.all { d -> d != null } }?.map { it!! }
    }

    /** DOMSnapshot 合成特征：两个不同元素同宽同高且严格首尾相接的垂直堆叠（uniform stack）。 */
    private fun looksLikeSyntheticStack(a: List<Double>, b: List<Double>): Boolean =
        a[2] == b[2] && a[3] == b[3] && (b[1] - a[1]) == a[3]

    /** 5. probe_input_click：btn 中心 mousePressed+mouseReleased → 计数器必须 +1。 */
    private suspend fun probeInputClick(conn: CdpConnection, sessionId: String) {
        try {
            val beforeEval = conn.evaluate("window.__c", sessionId)
            val before = beforeEval.value?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
            val rectText = conn.evaluate("JSON.stringify(document.getElementById('btn').getBoundingClientRect())", sessionId)
                .value?.let { (it as? JsonPrimitive)?.contentOrNull }
            val rect = parseRect(rectText) ?: return run {
                record("probe_input_click", "fail:btn-rect-missing")
            }
            val cx = rect[0] + rect[2] / 2.0
            val cy = rect[1] + rect[3] / 2.0
            conn.requestResult(
                "Input.dispatchMouseEvent",
                buildJsonObject {
                    put("type", "mousePressed")
                    put("x", cx); put("y", cy)
                    put("button", "main")
                    put("buttons", 1)
                    put("pointerType", "mouse")
                },
                sessionId,
            )
            conn.requestResult(
                "Input.dispatchMouseEvent",
                buildJsonObject {
                    put("type", "mouseReleased")
                    put("x", cx); put("y", cy)
                    put("button", "main")
                    put("buttons", 0)
                    put("pointerType", "mouse")
                },
                sessionId,
            )
            delay(300)
            val afterEval = conn.evaluate("window.__c", sessionId)
            val after = afterEval.value?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
            val ok = before != null && after != null && after == before + 1
            record("probe_input_click", if (ok) "ok" else "fail:counter-not-incremented")
            record("probe_input_click_detail", "before=$before | after=$after | clickAt=$cx,$cy | rect=$rect | beforeRaw=${trunc(beforeEval.raw, 200)} | afterRaw=${trunc(afterEval.raw, 200)}")
            if (!ok) noteError("Input.dispatchMouseEvent(click)", "counter before=$before after=$after (expected +1)")
        } catch (e: CdpError) {
            noteError(e.method, e.raw)
            record("probe_input_click", "fail:${trunc(e.raw, 300)}")
        }
    }

    /** 6. probe_key_events：focus inp 后 keyDown/char 序列 + insertText → value 必须变化。 */
    private suspend fun probeKeyEvents(conn: CdpConnection, sessionId: String) {
        try {
            conn.evaluate("document.getElementById('inp').focus()", sessionId)
            delay(100)
            val v0 = inputVal(conn, sessionId)
            conn.requestResult(
                "Input.dispatchKeyEvent",
                buildJsonObject {
                    put("type", "rawKeyDown")
                    put("text", "h")
                    put("unmodifiedText", "h")
                    put("windowsVirtualKeyCode", 72)
                    put("nativeVirtualKeyCode", 72)
                },
                sessionId,
            )
            conn.requestResult(
                "Input.dispatchKeyEvent",
                buildJsonObject {
                    put("type", "keyUp")
                    put("text", "h")
                    put("windowsVirtualKeyCode", 72)
                    put("nativeVirtualKeyCode", 72)
                },
                sessionId,
            )
            delay(200)
            val v1 = inputVal(conn, sessionId)
            conn.requestResult("Input.insertText", buildJsonObject { put("text", "Z") }, sessionId)
            delay(200)
            val v2 = inputVal(conn, sessionId)
            val ok = v0 != null && v2 != null && v2 != v0
            val via = buildList {
                if (v1 != null && v1 != v0) add("keydown-seq")
                if (v2 != null && v1 != null && v2 != v1) add("insertText")
            }.joinToString("+")
            record("probe_key_events", if (ok) "ok" else "fail:value-unchanged")
            record("probe_key_events_detail", "before=$v0 | afterKeySeq=$v1 | afterInsertText=$v2 | via=$via")
            if (!ok) noteError("Input.dispatchKeyEvent/insertText", "value before=$v0 after=$v2 (unchanged)")
        } catch (e: CdpError) {
            noteError(e.method, e.raw)
            record("probe_key_events", "fail:${trunc(e.raw, 300)}")
        }
    }

    private suspend fun inputVal(conn: CdpConnection, sessionId: String): String? =
        conn.evaluate("document.getElementById('inp').value", sessionId).value?.let { (it as? JsonPrimitive)?.contentOrNull }

    /** 7. probe_wheel_scroll：mouseWheel 于文档坐标 → window.scrollY 必须变化（文档可滚动）。 */
    private suspend fun probeWheelScroll(conn: CdpConnection, sessionId: String) {
        try {
            val y0 = conn.evaluate("window.scrollY", sessionId).value?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
            conn.requestResult(
                "Input.dispatchMouseEvent",
                buildJsonObject {
                    put("type", "mouseWheel")
                    put("x", 100.0); put("y", 100.0)
                    put("deltaX", 0.0); put("deltaY", 400.0)
                    put("pointerType", "mouse")
                },
                sessionId,
            )
            delay(400)
            val y1 = conn.evaluate("window.scrollY", sessionId).value?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
            val ok = y0 != null && y1 != null && y1 > y0
            record("probe_wheel_scroll", if (ok) "ok" else "fail:scrollY-unchanged")
            record("probe_wheel_scroll_detail", "scrollY before=$y0 after=$y1 (deltaY=400 @100,100)")
            if (!ok) noteError("Input.dispatchMouseEvent(mouseWheel)", "scrollY before=$y0 after=$y1")
        } catch (e: CdpError) {
            noteError(e.method, e.raw)
            record("probe_wheel_scroll", "fail:${trunc(e.raw, 300)}")
        }
    }

    /** 8. probe_screencast_frame：startScreencast → 等 screencastFrame（期间滚动驱动活动）→ ack → stop。 */
    private suspend fun probeScreencastFrame(conn: CdpConnection, sessionId: String) {
        try {
            val anchor = conn.seqNow()
            val startRes = conn.requestResult(
                "Page.startScreencast",
                buildJsonObject {
                    put("everyNthFrame", 1)
                    put("quality", 50)
                    put("maxFileSize", 500_000)
                },
                sessionId,
            )
            val jitter = conn.session.launch {
                repeat(20) {
                    delay(500)
                    runCatching { conn.evaluate("window.scrollBy(0,50)", sessionId) }
                }
            }
            val frameEv = conn.waitForEvent("Page.screencastFrame", anchor, 10_000)
            jitter.cancel()
            if (frameEv == null) {
                runCatching { conn.requestResult("Page.stopScreencast", buildJsonObject {}, sessionId) }
                record("probe_screencast_frame", "fail:no-frame-within-10s")
                record("probe_screencast_frame_detail", "startResponse=${trunc(startRes.toString())} | frame=<none>")
                noteError("Page.startScreencast", "no screencastFrame event within 10s (with scroll jitter)")
                return
            }
            val data = (frameEv.params["data"] as? JsonPrimitive)?.contentOrNull
            val metadata = frameEv.params["metadata"]
            // Obscura 的 ack 校验要求 sessionId 参数按其原始类型（可能是 integer）传递——原样转发，不转字符串
            val ackSessionElem = frameEv.params["sessionId"]
            conn.requestResult(
                "Page.screencastFrameAck",
                buildJsonObject { put("sessionId", ackSessionElem ?: JsonPrimitive(sessionId)) },
                sessionId,
            )
            val stopRes = conn.requestResult("Page.stopScreencast", buildJsonObject {}, sessionId)
            record("probe_screencast_frame", "ok")
            record(
                "probe_screencast_frame_detail",
                "startResponse=${trunc(startRes.toString())} | metadata=${trunc(metadata?.toString() ?: "<absent>", 300)} | " +
                    "dataLen=${data?.length ?: 0} | dataPrefix=${data?.take(24) ?: "<none>"} | " +
                    "frameEventSessionId=${frameEv.sessionId} | ackSessionParam=${ackSessionElem?.toString() ?: "<absent>"} | stopResponse=${trunc(stopRes.toString())}",
            )
        } catch (e: CdpError) {
            noteError(e.method, e.raw)
            record("probe_screencast_frame", "fail:${trunc(e.raw, 300)}")
            record("probe_screencast_frame_detail", "failedMethod=${e.method} | error=${e.raw}")
        }
    }

    /** 9. probe_tabs：createTarget(第二页) + getTargets + activateTarget + closeTarget。 */
    private suspend fun probeTabs(conn: CdpConnection) {
        try {
            val t2 = conn.requestResult("Target.createTarget", buildJsonObject { put("url", "about:blank") })
            val t2Id = (t2["targetId"] as? JsonPrimitive)?.content ?: ""
            val targets = conn.requestResult("Target.getTargets")
            val infos = targets["targetInfos"]?.jsonArray
            val summary = infos?.map {
                val o = it.jsonObject
                "${(o["targetId"] as? JsonPrimitive)?.content}:${(o["type"] as? JsonPrimitive)?.content}" +
                    ":attached=${(o["attached"] as? JsonPrimitive)?.content}"
            }?.joinToString(",") ?: "<absent>"
            conn.requestResult("Target.activateTarget", buildJsonObject { put("targetId", t2Id) })
            val closeRes = conn.requestResult("Target.closeTarget", buildJsonObject { put("targetId", t2Id) })
            record("probe_tabs", "ok")
            record("probe_tabs_detail", "t2=${trunc(t2.toString())} | targetCount=${infos?.size} | targets=$summary | close=${trunc(closeRes.toString())}")
        } catch (e: CdpError) {
            noteError(e.method, e.raw)
            record("probe_tabs", "fail:${trunc(e.raw, 300)}")
            record("probe_tabs_detail", "error=${e.raw}")
        }
    }

    /** 10. probe_getfullaxtree：getFullAXTree → 节点数 + generic 节点（完整树证据）+ 形状样例。 */
    private suspend fun probeGetFullAXTree(conn: CdpConnection, sessionId: String) {
        try {
            val res = conn.requestResult("Accessibility.getFullAXTree", buildJsonObject {}, sessionId)
            val nodes = res["nodes"]?.jsonArray
            val count = nodes?.size ?: 0
            val generic = nodes?.count {
                val role = (it.jsonObject["role"] as? JsonObject)?.let { r -> (r["value"] as? JsonPrimitive)?.contentOrNull }
                role == "generic"
            } ?: 0
            val sample = nodes?.take(2)?.joinToString(" || ") { trunc(it.toString(), 300) } ?: "<none>"
            val ok = nodes != null && count > 0 && generic > 0
            record("probe_getfullaxtree", if (ok) "ok" else "fail:${if (nodes == null) "no-nodes-field" else "empty-or-no-generic" }")
            record(
                "probe_getfullaxtree_detail",
                "topKeys=${res.keys.toList()} | nodeCount=$count | genericCount=$generic | shapeSample=$sample",
            )
        } catch (e: CdpError) {
            noteError(e.method, e.raw)
            record("probe_getfullaxtree", "fail:${trunc(e.raw, 300)}")
            record("probe_getfullaxtree_detail", "error=${e.raw}")
        }
    }

    /** 11. probe_domquery：DOM.getDocument + DOM.querySelector + DOM.getAttributes。 */
    private suspend fun probeDomQuery(conn: CdpConnection, sessionId: String) {
        try {
            val doc = conn.requestResult("DOM.getDocument", buildJsonObject {}, sessionId)
            val rootId = (doc["root"]?.jsonObject?.get("nodeId") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
            val found = conn.requestResult(
                "DOM.querySelector",
                buildJsonObject {
                    put("nodeId", rootId ?: 0)
                    put("selector", "#inp")
                },
                sessionId,
            )
            val inpNode = (found["nodeId"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
            val attrs = conn.requestResult(
                "DOM.getAttributes",
                buildJsonObject { put("nodeId", inpNode ?: 0) },
                sessionId,
            )
            // DOM.getAttributes 标准 CDP 形状：{"attributes":[key,value,key,value...]} 扁平数组
            val attrValues = attrs["attributes"]?.jsonArray?.map { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val idAttr = attrValues.windowed(2, 2).firstOrNull { it.firstOrNull() == "id" }?.getOrNull(1)
            val ok = rootId != null && inpNode != null && inpNode > 0 && idAttr == "inp"
            record("probe_domquery", if (ok) "ok" else "fail:chain-mismatch")
            record(
                "probe_domquery_detail",
                "root=${trunc(doc["root"]?.toString() ?: "<none>", 200)} | querySelector=${trunc(found.toString(), 200)} | " +
                    "attributes=${trunc(attrs.toString(), 200)} | idAttr=$idAttr",
            )
        } catch (e: CdpError) {
            noteError(e.method, e.raw)
            record("probe_domquery", "fail:${trunc(e.raw, 300)}")
            record("probe_domquery_detail", "error=${e.raw}")
        }
    }

    /** 12. probe_emulation：setDeviceMetricsOverride + 前后 innerWidth/innerHeight 记录。 */
    private suspend fun probeEmulation(conn: CdpConnection, sessionId: String) {
        try {
            val w0 = winDim(conn, sessionId, "window.innerWidth")
            val h0 = winDim(conn, sessionId, "window.innerHeight")
            val res = conn.requestResult(
                "Emulation.setDeviceMetricsOverride",
                buildJsonObject {
                    put("width", 1280)
                    put("height", 800)
                    put("deviceScaleFactor", 1)
                    put("mobile", false)
                },
                sessionId,
            )
            val w1 = winDim(conn, sessionId, "window.innerWidth")
            val h1 = winDim(conn, sessionId, "window.innerHeight")
            record("probe_emulation", "ok")
            record("probe_emulation_detail", "before=${w0}x${h0} | after=${w1}x${h1} | response=${trunc(res.toString())}")
        } catch (e: CdpError) {
            noteError(e.method, e.raw)
            record("probe_emulation", "fail:${trunc(e.raw, 300)}")
        }
    }

    private suspend fun winDim(conn: CdpConnection, sessionId: String, expr: String): Int? =
        conn.evaluate(expr, sessionId).value?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }

    /**
     * 13. probe_setfileinputfiles：DOM.setFileInputFiles（file input 节点）。
     * 已知失败模式：serve 不带 --allow-file-access 时方法被禁用（-32601）——如实落档 fail + error 原文。
     */
    private suspend fun probeSetFileInputFiles(conn: CdpConnection, sessionId: String, uploadFile: File) {
        try {
            val doc = conn.requestResult("DOM.getDocument", buildJsonObject {}, sessionId)
            val rootId = (doc["root"]?.jsonObject?.get("nodeId") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
            val found = conn.requestResult(
                "DOM.querySelector",
                buildJsonObject {
                    put("nodeId", rootId)
                    put("selector", "#file")
                },
                sessionId,
            )
            val nodeId = (found["nodeId"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
            val res = conn.requestResult(
                "DOM.setFileInputFiles",
                buildJsonObject {
                    put("nodeId", nodeId)
                    put("files", JsonArray(listOf(JsonPrimitive(uploadFile.absolutePath))))
                },
                sessionId,
            )
            val filesCount = conn.evaluate("document.getElementById('file').files.length", sessionId)
                .value?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
            record("probe_setfileinputfiles", "ok")
            record("probe_setfileinputfiles_detail", "nodeId=$nodeId | response=${trunc(res.toString())} | filesLength=$filesCount | file=${uploadFile.name}")
        } catch (e: CdpError) {
            noteError(e.method, e.raw)
            record("probe_setfileinputfiles", "fail:${trunc(e.raw, 300)}")
            record("probe_setfileinputfiles_detail", "error=${e.raw}")
        }
    }

    /** 14. probe_hover：mouseMoved 于 btn 附近（行为验证有限，能发能收即 ok）。 */
    private suspend fun probeHover(conn: CdpConnection, sessionId: String) {
        try {
            val rectText = conn.evaluate("JSON.stringify(document.getElementById('btn').getBoundingClientRect())", sessionId)
                .value?.let { (it as? JsonPrimitive)?.contentOrNull }
            val rect = parseRect(rectText) ?: return run {
                record("probe_hover", "fail:btn-rect-missing")
            }
            val res = conn.requestResult(
                "Input.dispatchMouseEvent",
                buildJsonObject {
                    put("type", "mouseMoved")
                    put("x", rect[0] + 5.0); put("y", rect[1] + 5.0)
                    put("button", "none")
                    put("pointerType", "mouse")
                },
                sessionId,
            )
            record("probe_hover", "ok")
            record("probe_hover_detail", "movedTo=${rect[0] + 5.0},${rect[1] + 5.0} | response=${trunc(res.toString())}")
        } catch (e: CdpError) {
            noteError(e.method, e.raw)
            record("probe_hover", "fail:${trunc(e.raw, 300)}")
        }
    }

    // ==================== 测试页（data:URL 源）：btn 计数器 / inp / 可滚动高内容 / file input ====================

    private val pageHtml = """
        <html><head><meta charset="utf-8"></head><body>
        <button id="btn" onclick="window.__c=(window.__c||0)+1">B</button>
        <input id="inp" value="x"/>
        <input type="file" id="file"/>
        <div id="tall" style="width:40px;height:3000px"></div>
        <script>window.__c=0;</script>
        </body></html>
    """.trimIndent()

    private fun trunc(s: String, n: Int = 300): String =
        if (s.length <= n) s else s.take(n) + "…(+${s.length - n})"
}
