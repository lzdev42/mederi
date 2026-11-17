package xyz.mederi.browser.cdp

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/** 收到的 CDP 命令帧记录（按到达顺序；id 为帧内原始值）。 */
data class CdpCommand(
    val id: String?,
    val method: String,
    val params: JsonObject,
    val sessionId: String?,
)

/**
 * CDP 假服务端（测试基座，纯测试工具，不依赖生产模块）：
 * Ktor server（CIO 引擎，loopback 随机端口）单 WS 端点 [path] 讲 CDP 线协议——
 * 入站 `{"id":N,"method":...,"params":{...},"sessionId"?}` → 出站 `{"id":N,"result":{...}}` /
 * `{"id":N,"error":{"code","message"}}`，并可主动向已连客户端推事件帧
 * `{"method","params","sessionId"?}`。
 *
 * 能力（对应 ObscuraCdpClientTest 断言）：
 * ① 命令记录：[receivedCommands] / [receivedCommandsFor]（可查询收到的 method/params/sessionId 与顺序）
 * ② 脚本化应答：[onMethod]（method → result handler）/ [queueMethod]（FIFO 队列式）/
 *   [onMethodError]（method → error 应答，JSON-RPC code + message）
 * ③ 主动推事件帧：[pushEvent]（params 原样透传，含 integer 句柄）
 * ④ `Target.attachToTarget` 默认回固定显式 sessionId [attachSessionId]（验证 flat 路由；
 *   形状照 ST1 探针实测的显式 attach 应答 `{"sessionId":"page-1-session-1"}`）
 *
 * 无脚本 method 回 CDP 风格 -32601（method not found），与真服务端行为一致。
 */
class FakeCdpServer(
    private val path: String = "/devtools/browser",
    private val attachSessionId: String = "page-1-session-1",
) {

    private val json = Json
    private val methodHandlers = ConcurrentHashMap<String, suspend (JsonObject) -> JsonObject>()
    private val methodErrors = ConcurrentHashMap<String, Pair<Int, String>>()
    private val queuedResults = ConcurrentHashMap<String, ConcurrentLinkedDeque<JsonObject>>()
    private val commands = CopyOnWriteArrayList<CdpCommand>()
    private val sessions = CopyOnWriteArrayList<DefaultWebSocketServerSession>()
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var port = 0

    /** 启动后的 WS 端点（随机端口）：`ws://127.0.0.1:<port><path>`。 */
    val endpoint: String get() = "ws://127.0.0.1:$port$path"

    /** 当前已连接客户端 session 数（close 断言用：客户端关闭后降为 0）。 */
    val activeSessions: Int get() = sessions.size

    /** 启动服务端（loopback 随机端口，非阻塞）；端口经 [endpoint] 读取。 */
    suspend fun start() {
        require(engine == null) { "FakeCdpServer 已启动" }
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket(path) {
                    sessions.add(this)
                    try {
                        for (frame in incoming) {
                            // 默认（非 raw）webSocket：控制帧（ping/pong/close）由服务端插件处理
                            if (frame is Frame.Text) handle(frame.readText(), this)
                        }
                    } finally {
                        sessions.remove(this)
                    }
                }
            }
        }
        engine = server
        server.start(wait = false)
        port = server.engine.resolvedConnectors().first().port
    }

    /** 停服务端并释放端口（幂等）。 */
    fun stop() {
        runCatching { engine?.stop(100, 2_000) }
        engine = null
        port = 0
    }

    /** ② 为 [method] 注册脚本化应答 handler（后注册覆盖先注册）。 */
    fun onMethod(method: String, result: suspend (params: JsonObject) -> JsonObject) {
        methodHandlers[method] = result
    }

    /** ② 为 [method] 注册 error 应答（JSON-RPC 风格 code + message）。 */
    fun onMethodError(method: String, code: Int, message: String) {
        methodErrors[method] = code to message
    }

    /** ② 队列式脚本化应答：对 [method] 的第 n 次调用消费第 n 个入队 result。 */
    fun queueMethod(method: String, result: JsonObject) {
        queuedResults.getOrPut(method) { ConcurrentLinkedDeque() }.addLast(result)
    }

    /** ③ 向全部已连客户端主动推事件帧 `{"method","params","sessionId"?}`（params 原样透传）。 */
    suspend fun pushEvent(method: String, params: JsonObject = buildJsonObject {}, sessionId: String? = null) {
        val frame = buildJsonObject {
            put("method", method)
            put("params", params)
            if (sessionId != null) put("sessionId", JsonPrimitive(sessionId))
        }
        sessions.forEach { s ->
            runCatching { s.outgoing.send(Frame.Text(frame.toString())) }
        }
    }

    /** ① 收到的全部命令帧（按到达顺序）。 */
    val receivedCommands: List<CdpCommand> get() = commands.toList()

    /** ① 按 [method] 过滤的命令帧。 */
    fun receivedCommandsFor(method: String): List<CdpCommand> = commands.filter { it.method == method }

    // ==================== 入站帧处理 ====================

    /** 解析一条入站命令帧：记录 → 按脚本应答（④ attach 固定 session；无脚本 → -32601）。 */
    private suspend fun handle(text: String, session: DefaultWebSocketServerSession) {
        val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = msg["id"] ?: return // 事件帧/非命令帧不回应
        val method = (msg["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val params = msg["params"]?.let { if (it is JsonObject) it else null } ?: buildJsonObject {}
        commands.add(
            CdpCommand(
                id = id.idString(),
                method = method,
                params = params,
                sessionId = (msg["sessionId"] as? JsonPrimitive)?.contentOrNull,
            ),
        )
        val error = methodErrors[method]
        val scripted: JsonObject? = when {
            error != null -> null
            method == "Target.attachToTarget" ->
                buildJsonObject { put("sessionId", JsonPrimitive(attachSessionId)) } // ④ 固定显式 session
            else -> methodHandlers[method]?.invoke(params)
                ?: queuedResults.getOrPut(method) { ConcurrentLinkedDeque() }.removeFirst()
        }
        val errorBody = error?.let { (code, message) ->
            buildJsonObject {
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive(message))
            }
        }
        val resultBody = if (errorBody != null) null else scripted
        val resp = buildJsonObject {
            put("id", id)
            if (resultBody != null) {
                put("result", resultBody)
            } else {
                put(
                    "error",
                    errorBody ?: buildJsonObject {
                        put("code", JsonPrimitive(-32601))
                        put("message", JsonPrimitive("FakeCdpServer: no scripted response for $method"))
                    },
                )
            }
        }
        runCatching { session.outgoing.send(Frame.Text(resp.toString())) }
    }

    /** 命令 id 原始值（整数 id 记为十进制字符串；非 primitive 原样 toString）。 */
    private fun JsonElement?.idString(): String? = when (this) {
        null -> null
        is JsonPrimitive -> content
        else -> toString()
    }
}
