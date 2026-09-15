package xyz.mederi.browser.bidi

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import xyz.mederi.browser.bidi.BiDiLog

internal class BiDiTransport(
    private val wsUrl: String,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 30_000L
) {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val idCounter = AtomicInteger(0)
    private val sendChannel = Channel<String>(Channel.UNLIMITED)
    private var session: DefaultClientWebSocketSession? = null
    private var client: HttpClient? = null
    @Volatile private var connected = false

    val isConnected: Boolean get() = connected

    val events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)

    suspend fun connect() {
        BiDiLog.info("BiDiTransport.connect: BEGIN wsUrl=$wsUrl")
        val c = HttpClient(CIO) {
            install(WebSockets)
            engine {
                requestTimeout = 0
            }
        }
        client = c
        session = c.webSocketSession(wsUrl)
        connected = true
        BiDiLog.success("BiDiTransport.connect: WebSocket session established, connected=$connected")
        scope.launch { senderLoop() }
        scope.launch { receiverLoop() }
    }

    private suspend fun senderLoop() {
        val s = session ?: return
        BiDiLog.info("BiDiTransport.senderLoop: started")
        try {
            for (msg in sendChannel) {
                s.send(msg)
            }
            BiDiLog.info("BiDiTransport.senderLoop: sendChannel closed, exiting")
        } catch (e: Exception) {
            BiDiLog.error("BiDiTransport.senderLoop: exception: ${e::class.simpleName}: ${e.message}")
            if (connected) failAll("send error: ${e.message}")
        }
    }

    private suspend fun receiverLoop() {
        val s = session ?: return
        BiDiLog.info("BiDiTransport.receiverLoop: started, waiting for frames...")
        var frameCount = 0
        try {
            for (frame in s.incoming) {
                frameCount++
                if (frame !is Frame.Text) {
                    BiDiLog.info("BiDiTransport.receiverLoop: frame#$frameCount non-text type=${frame.frameType}")
                    continue
                }
                val text = frame.readText()
                val json = try {
                    Json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    BiDiLog.warn("BiDiTransport.receiverLoop: frame#$frameCount JSON parse failed: ${e.message}, raw=${text.take(200)}")
                    continue
                }
                val id = json["id"]?.jsonPrimitive?.intOrNull
                if (id != null) {
                    val type = json["type"]?.jsonPrimitive?.contentOrNull
                    BiDiLog.info("BiDiTransport.receiverLoop: frame#$frameCount response id=$id type=$type")
                    pending.remove(id)?.complete(json)
                } else {
                    val type = json["type"]?.jsonPrimitive?.contentOrNull
                    val method = json["method"]?.jsonPrimitive?.contentOrNull
                    BiDiLog.info("BiDiTransport.receiverLoop: frame#$frameCount event type=$type method=$method")
                    if (type == "event" || method != null) {
                        scope.launch { events.emit(json) }
                    }
                }
            }
            BiDiLog.warn("BiDiTransport.receiverLoop: incoming channel closed (EOF), total frames=$frameCount, connected=$connected")
        } catch (e: Exception) {
            BiDiLog.error("BiDiTransport.receiverLoop: exception: ${e::class.simpleName}: ${e.message}, total frames=$frameCount")
            if (connected) failAll("receive error: ${e.message}")
        }
    }

    suspend fun send(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        if (!connected) throw BiDiException("not_connected", "Transport not connected")
        val id = idCounter.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred

        val command = buildJsonObject {
            put("type", JsonPrimitive("command"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        val paramsPreview = params.toString().take(160)
        BiDiLog.info("BiDiTransport.send: → id=$id method=$method params=$paramsPreview connected=$connected pending=${pending.size}")
        sendChannel.send(command.toString())

        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                BiDiLog.error("BiDiTransport.send: ✗ TIMEOUT id=$id method=$method after ${timeoutMs}ms, transport.connected=$connected, pending=${pending.size}")
                pending.remove(id)
                throw BiDiException("timeout", "BiDi command '$method' timed out after ${timeoutMs}ms")
            }

        val type = response["type"]?.jsonPrimitive?.contentOrNull
        if (type == "error" || type == "exception") {
            val error = response["error"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val message = response["message"]?.jsonPrimitive?.contentOrNull ?: ""
            BiDiLog.error("BiDiTransport.send: ✗ ERROR id=$id method=$method type=$type error=$error message=$message")
            throw BiDiErrorException(error, message)
        }

        BiDiLog.info("BiDiTransport.send: ✓ id=$id method=$method type=$type")
        return response["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun failAll(reason: String) {
        BiDiLog.warn("BiDiTransport.failAll: reason=$reason, failing ${pending.size} pending requests, connected was=$connected")
        connected = false
        val ex = BiDiException("connection_closed", reason)
        pending.values.forEach { it.completeExceptionally(ex) }
        pending.clear()
    }

    suspend fun close() {
        BiDiLog.info("BiDiTransport.close: BEGIN, connected=$connected, pending=${pending.size}")
        connected = false
        sendChannel.close()
        try { session?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        failAll("closed")
        BiDiLog.info("BiDiTransport.close: END")
    }
}
