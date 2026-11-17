package xyz.mederi.browser.obscura

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import xyz.mederi.api.exception.MederiException
import xyz.mederi.debug.DebugLog
import xyz.mederi.mcp.runtime.McpRuntime
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 入站 CDP 事件（有 `method` 键、无 `id` 键的帧）。
 *
 * - [params] **原样透传**，本模块不篡改/丢弃：`Page.screencastFrame` params 里的 `sessionId`
 *   是 integer 内部句柄（与 flat 路由的字符串 sessionId 不同），上层（ST4）调用
 *   `Page.screencastFrameAck` 时必须原样转发该数值（转字符串会报 "sessionId must be an integer"）。
 * - [sessionId] 是事件来源所属的字符串 session（flat 路由身份），浏览器级事件可能为 null。
 */
data class CdpEvent(
    val method: String,
    val params: JsonObject,
    val sessionId: String?,
)

/**
 * CDP 命令/通讯层异常，ST3/ST4 的唯一捕获面（[MederiException] 子类）。
 *
 * - [code]：`error` 应答的 CDP 错误码（JSON-RPC 风格，如 -32601）；`null` 表示本地通讯层
 *   失败（未连接/已关闭/等待超时/进程启动失败/二进制缺失）。
 * - [raw]：error JSON 原文（诊断用）。
 */
class CdpCommandException(
    val code: Int?,
    message: String,
    val raw: String? = null,
    cause: Throwable? = null,
) : MederiException(message, cause)

/**
 * Obscura CDP 通讯模块（ST2，替代原 MCP 路线（ST5 已删除）的底座）：
 * obscura serve 子进程生命周期 + WebSocket 连接 + JSON-RPC 请求/应答配对 + 事件广播。
 * 是 ST3（Control/Locator/快照层）与 ST4（screencast）的唯一通道。
 * 本模块不做域 API 抽象（navigate/click 等域语义由 ST3 封装），只做透传命令 + 事件通道。
 *
 * 线格式与 session 模型（ST1 探针实测，见 `core/build/obscura-cdp-probe.json`）：
 * - 命令 `{"id":N,"method":...,"params":{...}}`；应答 `{"id":N,"result":{...}}` 或
 *   `{"id":N,"error":{"code":...,"message":"..."}}`；事件 `{"method":...,"params":{...},"sessionId":"..."}`。
 * - `Target.createTarget` 后 obscura 自动附加一个 session（`Target.attachedToTarget` 事件，
 *   sessionId 如 "page-1-session"）；显式 `Target.attachToTarget(flatten=true)` 应答**另一个**
 *   sessionId（"page-1-session-1"）——两个 session 并存且都收事件，只有显式 attach 返回的
 *   sessionId 是**唯一路由身份**，后续页面级命令一律携带它（flat 模式）。
 *
 * 启动（实测事实，不可配）：`serve --port 0 --stealth --allow-private-network --ready-file <tmp>`——
 * stealth 与 allow-private-network 是用户既有拍板（本机代理 fake-ip 场景需要），无开关。
 * ready-file 是 JSON `{"pid":..,"host":"127.0.0.1","port":..,"websocket_path":"/devtools/browser"}`
 * （kotlinx.serialization 解析，禁止手拼/正则），WS 端点 = `ws://{host}:{port}{websocket_path}`；
 * [READY_FILE_POLL_MS] 间隔轮询、[READY_FILE_TIMEOUT_MS] 上界；loopback 绑定无需 OBSCURA_CDP_TOKEN。
 *
 * 进程回收（reap 语义；探针实测 `--workers 1` 是单进程无子进程，destroy 即回收）：
 * `close` → 收循环退出 → 关 WS → `destroy` → [REAP_GRACE_SECONDS]s 宽限断言 `!isAlive` → 仍活则
 * `destroyForcibly`。取消路径下回收降级为后台线程，不留孤儿进程。
 *
 * 线程边界：全部为 suspend API，阻塞 IO（进程 waitFor）包裹 [Dispatchers.IO]，调用方无需切调度器。
 */
class ObscuraCdpClient(
    /**
     * obscura 二进制定位结果（[ObscuraBinaryLocator.resolve] 等）；`null` 时 [start] 抛
     * [CdpCommandException]（明确错误，不静默）。
     */
    val binary: String?,
    /**
     * 测试注入路径（[connect]）：非 null 时 [start] 跳过进程 spawn，直连既有 WS 端点
     * （FakeCdpServer 单测不起真 obscura；真二进制验证在 ST1/ST6）。
     */
    val externalEndpoint: String? = null,
    /** 接收循环所在协程作用域；`null` = 内部 SupervisorJob + [Dispatchers.IO]（实例自持）。 */
    scope: CoroutineScope? = null,
    /** [send] 等待应答的默认上界（毫秒），调用方可配。 */
    val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
) {

    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json

    /** 待配对应答表：命令 id（原始值）→ 应答 deferred；连接断开时全部以异常完成。 */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicInteger(1)

    /** 入站事件广播：replay 0 / 额外容量 256 / DropOldest——防慢消费者背压卡死接收循环。 */
    private val eventFlow = MutableSharedFlow<CdpEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 入站 CDP 事件广播（[CdpEvent]）；连接断开时发射一次终结标记（method=[CONNECTION_CLOSED_METHOD]），此后不再收帧。 */
    val events: SharedFlow<CdpEvent> = eventFlow

    /** obscura serve 子进程（[connect] 注入路径与 [close] 后为 null）；暴露供 ST6 进程回收断言。 */
    var process: Process? = null
        private set

    private var client: HttpClient? = null
    private var wsSession: DefaultClientWebSocketSession? = null
    private var receiveJob: Job? = null
    private var readyFile: File? = null
    private var logFile: File? = null
    private var readyEndpoint: String? = null
    private var closed = false

    companion object {
        private const val LAYER = "ObscuraCdp"

        /** 默认应答等待上界 30s。 */
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** ready-file 轮询间隔 / 上界。 */
        const val READY_FILE_POLL_MS = 50L
        const val READY_FILE_TIMEOUT_MS = 10_000L

        /** 进程回收宽限（秒）：SIGTERM → 宽限不退 → SIGKILL。 */
        const val REAP_GRACE_SECONDS = 2L

        /** 连接断开后发射到 [ObscuraCdpClient.events] 的终结标记 method 值（非真实 CDP 事件，上层据此判断断连）。 */
        const val CONNECTION_CLOSED_METHOD = "<closed>"

        /**
         * 测试/诊断路径：跳过 obscura 子进程，直连既有 WS 端点（FakeCdpServer 单测用）。
         * 等价于 `ObscuraCdpClient(binary = null, externalEndpoint = endpoint, ...)`。
         */
        fun connect(
            endpoint: String,
            scope: CoroutineScope? = null,
            commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        ): ObscuraCdpClient = ObscuraCdpClient(null, endpoint, scope, commandTimeoutMs)
    }

    /**
     * 起子进程 + 等 ready-file + WS 连接 + 接收循环启动。
     *
     * 幂等且可重复调用：运行中（WS 已连、接收循环存活）直接返回；[close] 后再次调用会重新
     * spawn / 重连；子进程已死而连接尚存的病态状态同样自愈（重新 spawn + 重连）。
     * [externalEndpoint] 路径跳过进程 spawn。失败抛 [CdpCommandException]，已 spawn 的进程先回收，不留孤儿。
     */
    suspend fun start() {
        if (wsSession != null && receiveJob?.isActive == true) return // 已运行（幂等）
        releaseConnection()
        closed = false

        val external = externalEndpoint
        if (external != null) {
            connect(external)
            return
        }
        val bin = binary ?: throw CdpCommandException(
            code = null,
            message = "obscura 二进制未找到（ObscuraBinaryLocator.resolve() 返回 null），无法 spawn serve 子进程",
        )
        val old = process
        process = null
        if (old == null || !old.isAlive) {
            spawnAndAwaitReady(bin) // 失败已回收新进程并抛 [CdpCommandException]
        }
        val endpoint = readyEndpoint ?: throw CdpCommandException(
            code = null,
            message = "ready-file 端点缺失（子进程存活但未解析出 WS 端点），请 close() 后重新 start()",
        )
        connect(endpoint)
    }

    /**
     * 发 CDP 命令并等待配对应答，返回 result 对象。透传语义，无域逻辑。
     *
     * - 应答 `{"id","result"}` → 返回 result（result 缺失 = 无数据，返回空对象；非对象 result
     *   按 ST1 探针惯例包进 `_raw_non_object_result` 键，不静默丢弃）。
     * - 应答 `{"id","error"}` → 抛 [CdpCommandException]（携带 error.code + error.message + 原文）。
     * - [commandTimeoutMs] 内无应答 / 连接已断开 / 未 start → 抛 [CdpCommandException]（不挂死）。
     * - 协程取消原样透传（不包装为业务故障）。
     *
     * @param sessionId 携带时帧加 `"sessionId"` 字段（flat 路由，[attachToTarget] 返回的显式 session）。
     */
    suspend fun send(method: String, params: JsonObject = buildJsonObject {}, sessionId: String? = null): JsonObject {
        val session = wsSession ?: throw CdpCommandException(
            code = null,
            message = "ObscuraCdpClient 未连接（先 start()，或已 close/断连）",
        )
        val id = nextId.getAndIncrement()
        val frame = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("method", method)
            put("params", params)
            if (sessionId != null) put("sessionId", JsonPrimitive(sessionId))
        }
        val key = id.toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[key] = deferred
        try {
            try {
                session.outgoing.send(Frame.Text(frame.toString()))
            } catch (e: ClosedSendChannelException) {
                throw CdpCommandException(
                    code = null,
                    message = "CDP 连接已断开（${method} 无法发送）",
                    cause = e,
                )
            }
            val resp = try {
                withTimeout(commandTimeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw CdpCommandException(
                    code = null,
                    message = "CDP $method 在 ${commandTimeoutMs}ms 内无应答",
                    cause = e,
                )
            }
            val err = resp["error"]
            if (err != null) {
                val errObj = runCatching { err.jsonObject }.getOrNull()
                val code = (errObj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
                val message = (errObj?.get("message") as? JsonPrimitive)?.contentOrNull ?: err.toString()
                throw CdpCommandException(code, "CDP $method error: $message", err.toString())
            }
            return when (val r = resp["result"]) {
                null -> buildJsonObject {}
                is JsonObject -> r
                else -> buildJsonObject { put("_raw_non_object_result", r) }
            }
        } finally {
            pending.remove(key)
        }
    }

    /**
     * 显式 `Target.attachToTarget(flatten=true)`，返回显式 attach 得到的 sessionId。
     *
     * 封装 ST1 实测坑：createTarget 后 obscura 自动附加一个 session（`Target.attachedToTarget`
     * 事件），显式 attachToTarget 再返回另一个 sessionId——两个并存且都收事件，**只有这里返回的
     * 显式 sessionId 是路由身份**；后续页面级命令一律携带它。
     */
    suspend fun attachToTarget(targetId: String): String {
        val result = send(
            "Target.attachToTarget",
            buildJsonObject {
                put("targetId", targetId)
                put("flatten", true)
            },
        )
        val sessionId = (result["sessionId"] as? JsonPrimitive)?.contentOrNull
        if (sessionId.isNullOrEmpty()) {
            throw CdpCommandException(code = null, message = "Target.attachToTarget 未返回 sessionId: $result")
        }
        return sessionId
    }

    /**
     * 幂等关闭：停接收循环 → 关 WS → 回收子进程（SIGTERM → 2s 宽限 → SIGKILL，[Dispatchers.IO] 内
     * 阻塞等待；取消路径降级后台线程保证不留孤儿）→ 清理临时文件。[close] 后可再次 [start]。
     */
    suspend fun close() {
        if (closed) return
        closed = true
        releaseConnection()
        val p = process
        process = null
        readyEndpoint = null
        if (p != null) {
            try {
                withContext(Dispatchers.IO) { p.reapBlocking() }
            } catch (e: CancellationException) {
                Thread { runCatching { p.reapBlocking() } }.start() // 取消也不能留孤儿进程
                throw e
            }
        }
        runCatching { readyFile?.delete() }
        runCatching { logFile?.delete() }
        readyFile = null
        logFile = null
        DebugLog.info(LAYER, "CDP client closed")
    }

    // ==================== 子进程与 ready-file ====================

    /**
     * spawn `serve --port 0 --stealth --allow-private-network --ready-file <tmp>`（stealth 与
     * allow-private-network 固化，用户既有拍板）并轮询等 ready-file 完整可解析（kotlinx）。
     * 环境合并父进程（[McpRuntime.environment]）。stdout/stderr 落临时日志防管道死锁。
     */
    private suspend fun spawnAndAwaitReady(bin: String) {
        runCatching { readyFile?.delete() }
        runCatching { logFile?.delete() }
        val ready = Files.createTempFile("obscura-cdp-ready", ".json").toFile()
        val log = Files.createTempFile("obscura-cdp-serve", ".log").toFile()
        val p = ProcessBuilder(
            listOf(
                bin, "serve", "--port", "0",
                "--stealth", "--allow-private-network", // 固化，无开关
                "--ready-file", ready.absolutePath,
            ),
        )
            .redirectOutput(log)
            .redirectErrorStream(true)
            .apply {
                val environment = McpRuntime.environment(emptyMap())
                environment().clear()
                environment().putAll(environment)
            }
            .start()
        process = p
        readyFile = ready
        logFile = log
        try {
            val content = awaitReadyFile(ready, p)
            readyEndpoint = parseWsEndpoint(content)
        } catch (e: CancellationException) {
            failSpawn(p, ready, log)
            throw e
        } catch (t: Throwable) {
            failSpawn(p, ready, log)
            throw CdpCommandException(
                code = null,
                message = "obscura serve 启动失败: ${t.message ?: t}",
                cause = t,
            )
        }
        DebugLog.info(LAYER, "obscura serve started: pid=${p.pid()} ws=$readyEndpoint")
    }

    /** spawn 失败/取消路径：后台线程回收进程 + 清临时文件（fire-and-forget，免疫协程取消）。 */
    private fun failSpawn(p: Process, ready: File, log: File) {
        readyFile = null
        logFile = null
        Thread {
            runCatching { p.reapBlocking() }
            runCatching { ready.delete() }
            runCatching { log.delete() }
        }.start()
    }

    /** 轮询 ready-file（[READY_FILE_POLL_MS] 间隔、[READY_FILE_TIMEOUT_MS] 上界）至出现且内容完整可解析。 */
    private suspend fun awaitReadyFile(file: File, p: Process): String {
        val deadline = System.currentTimeMillis() + READY_FILE_TIMEOUT_MS
        while (true) {
            val content = file.takeIf { it.length() > 0 }?.readText()?.trim().orEmpty()
            if (content.isNotEmpty()) {
                runCatching { json.parseToJsonElement(content).jsonObject }
                    .getOrNull()
                    ?.let { return content } // 仅 JSON 完整可解析才返回（防半写）
            }
            if (!p.isAlive) {
                val tail = runCatching { logFile?.readText()?.takeLast(500) }.getOrNull() ?: ""
                throw CdpCommandException(
                    code = null,
                    message = "obscura serve 进程提前退出（ready-file 未落盘）: ${tail.take(300)}",
                )
            }
            if (System.currentTimeMillis() >= deadline) {
                throw CdpCommandException(
                    code = null,
                    message = "ready-file 未在 ${READY_FILE_TIMEOUT_MS}ms 内落盘（obscura serve 启动过慢或失败）",
                )
            }
            delay(READY_FILE_POLL_MS)
        }
    }

    /**
     * ready-file 端点解析（kotlinx，禁止手拼/正则）：
     * `{"pid":..,"host":"127.0.0.1","port":52686,"websocket_path":"/devtools/browser"}`
     * → `ws://{host}:{port}{websocket_path}`。
     */
    private fun parseWsEndpoint(ready: String): String {
        val obj = json.parseToJsonElement(ready).jsonObject
        val host = (obj["host"] as? JsonPrimitive)?.contentOrNull ?: "127.0.0.1" // loopback 绑定默认值
        val port = (obj["port"] as? JsonPrimitive)?.contentOrNull
            ?: throw CdpCommandException(code = null, message = "ready-file 缺 port 字段: $ready")
        val path = (obj["websocket_path"] as? JsonPrimitive)?.contentOrNull ?: "/devtools/browser"
        return "ws://$host:$port$path"
    }

    // ==================== WS 连接与接收循环 ====================

    /** 建立 WS 连接并启动独立接收循环（[scope]；默认实例自持 SupervisorJob+IO）。 */
    private suspend fun connect(endpoint: String) {
        val c = HttpClient(CIO) {
            // 长连接保活：20s ping（CDP 服务端协议层自动回 pong）
            WebSockets {
                pingIntervalMillis = 20_000L
            }
        }
        val session = try {
            c.webSocketSession(endpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            runCatching { c.close() }
            throw CdpCommandException(
                code = null,
                message = "CDP websocket 连接 $endpoint 失败: ${t.message ?: t}",
                cause = t,
            )
        }
        client = c
        wsSession = session
        receiveJob = scope.launch { receiveLoop(session) }
        DebugLog.info(LAYER, "CDP websocket connected: $endpoint")
    }

    /** 释放当前连接（幂等）：停接收循环 → 关 session → 关 client。 */
    private fun releaseConnection() {
        val job = receiveJob
        receiveJob = null
        job?.cancel() // 循环 finally 会失败全部 pending + 发射终结事件
        runCatching { wsSession?.cancel() }
        runCatching { client?.close() }
        wsSession = null
        client = null
    }

    /** 接收循环：逐帧 kotlinx 解析 → 有 id 键配对应答；有 method 键无 id 键广播事件。 */
    private suspend fun receiveLoop(session: DefaultClientWebSocketSession) {
        try {
            while (true) {
                val frame = session.incoming.receive()
                when (frame) {
                    is Frame.Text -> dispatch(String(frame.data, Charsets.UTF_8))
                    is Frame.Ping -> runCatching { session.outgoing.send(Frame.Pong(frame.data)) }
                    is Frame.Close -> break
                    else -> Unit
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // 连接关闭（对端或 close() 发起）
        } catch (e: CancellationException) {
            throw e // close() 取消接收循环
        } catch (e: Throwable) {
            DebugLog.error(LAYER, "CDP 接收循环异常退出", e)
        } finally {
            failPending(CdpCommandException(code = null, message = "CDP 连接已断开，命令不会再有应答"))
            eventFlow.tryEmit(CdpEvent(CONNECTION_CLOSED_METHOD, buildJsonObject {}, null))
        }
    }

    /** 入站帧分发（解析失败安全跳过，不杀循环）。 */
    private fun dispatch(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val id = obj["id"]
        if (id != null) {
            val key = (id as? JsonPrimitive)?.content ?: id.toString()
            pending.remove(key)?.complete(obj)
            return
        }
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: return
        // 事件：params 原样透传（screencastFrame 的 integer sessionId 句柄不篡改）；
        // 顶层 sessionId 字段（字符串）是 flat 路由身份
        eventFlow.tryEmit(
            CdpEvent(
                method = method,
                params = obj["params"]?.let { if (it is JsonObject) it else null } ?: buildJsonObject {},
                sessionId = (obj["sessionId"] as? JsonPrimitive)?.contentOrNull,
            ),
        )
    }

    /** 连接断开：所有 pending 以 [CdpCommandException] 完成（调用方不会挂死）。 */
    private fun failPending(reason: CdpCommandException) {
        pending.values.filter { !it.isCompleted }.forEach { it.completeExceptionally(reason) }
        pending.clear()
    }

    // ==================== 进程回收 ====================

    /** SIGTERM → 2s 宽限仍活 → SIGKILL。返回是否已退出。 */
    private fun Process.reapBlocking(): Boolean {
        destroy()
        var exited = try {
            waitFor(REAP_GRACE_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            isAlive
        }
        if (!exited) {
            destroyForcibly()
            try {
                exited = waitFor(REAP_GRACE_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                exited = false
            }
            if (!exited) DebugLog.error(LAYER, "obscura 进程 destroyForcibly 后仍存活: pid=${pid()}")
        }
        return exited
    }
}
