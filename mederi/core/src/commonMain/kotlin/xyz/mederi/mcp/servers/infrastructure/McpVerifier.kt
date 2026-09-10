package xyz.mederi.mcp.servers.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.mederi.debug.DebugLog
import xyz.mederi.mcp.servers.domain.McpConnection
import xyz.mederi.mcp.servers.domain.McpVerifyResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.TimeSource

/**
 * MCP 可用性验证器：对目标 server 发起一次 MCP `initialize` 握手。
 *
 * 握手成功 = 协议层可用（不校验 API key 的业务有效性）。
 *
 * - remote：优先 POST `initialize`（streamable-http 语义）；响应解析不出握手结果或返回非 2xx 时，
 *   回退 GET 探测 SSE（2xx + text/event-stream = 活着）。
 * - stdio：拉起进程（合并父环境变量，保证 PATH 可用），stdin 写 initialize，stdout 等合法响应，
 *   超时或异常退出即失败；无论成败最终销毁进程。
 *
 * 请求体用 @Serializable 模型序列化；响应是 wild 数据，用 JsonElement 宽松遍历。
 *
 * 将来内核 MCP 引擎接入 Koog `agents-mcp` 后可替换为正式客户端实现，签名不变。
 *
 * @param timeout 单次握手总超时（连接 + 响应），默认 10 秒。
 */
class McpVerifier(private val timeout: Duration = Duration.ofSeconds(10)) {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    suspend fun verify(connection: McpConnection): McpVerifyResult = withContext(Dispatchers.IO) {
        when (connection) {
            is McpConnection.Remote -> verifyRemote(connection)
            is McpConnection.Stdio -> verifyStdio(connection)
        }
    }

    // ==================== remote ====================

    private fun verifyRemote(remote: McpConnection.Remote): McpVerifyResult {
        val started = TimeSource.Monotonic.markNow()
        val elapsed = { started.elapsedNow().inWholeMilliseconds }

        try {
            val postResponse = httpClient.send(
                remoteRequestBuilder(remote)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(initializeRequest()))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )

            if (postResponse.statusCode() in 200..299) {
                val found = findInitializeResult(postResponse.body())
                    ?: return McpVerifyResult.failure(
                        elapsed(),
                        "HTTP ${postResponse.statusCode()}，但响应中没有 initialize 握手结果"
                    )
                val rpcError = rpcErrorMessage(found.first)
                if (rpcError != null) return McpVerifyResult.failure(elapsed(), "initialize 被拒绝: $rpcError")
                return McpVerifyResult.success(elapsed(), serverInfoOf(found.second))
            }

            // 非 2xx → 回退 GET 探测 SSE（某些 sse 端点不接受 POST initialize 到同一 URL）
            val getResponse = httpClient.send(
                remoteRequestBuilder(remote)
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            val isEventStream = getResponse.headers()
                .firstValue("content-type")
                .orElse("")
                .contains("text/event-stream")

            return if (getResponse.statusCode() in 200..299 && isEventStream) {
                McpVerifyResult.success(elapsed(), null)
            } else {
                McpVerifyResult.failure(
                    elapsed(),
                    "POST initialize 返回 HTTP ${postResponse.statusCode()}，GET SSE 探测返回 HTTP ${getResponse.statusCode()}"
                )
            }
        } catch (e: Exception) {
            DebugLog.error("McpVerifier", "remote verify failed: ${remote.url} - ${e.message}", e)
            return McpVerifyResult.failure(elapsed(), "连接失败: ${e.message}")
        }
    }

    // ==================== stdio ====================

    private suspend fun verifyStdio(stdio: McpConnection.Stdio): McpVerifyResult {
        val started = TimeSource.Monotonic.markNow()

        val process = try {
            ProcessBuilder(listOf(stdio.command) + stdio.args)
                .apply { environment().putAll(stdio.env) }
                .start()
        } catch (e: Exception) {
            DebugLog.error("McpVerifier", "stdio verify failed: ${stdio.command} - ${e.message}", e)
            return McpVerifyResult.failure(0, "无法启动进程: ${e.message}")
        }

        try {
            process.outputWriter(Charsets.UTF_8).use { writer ->
                writer.write(initializeRequest())
                writer.write("\n")
                writer.flush()
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val deadlineMs = timeout.toMillis()
            val elapsed = { started.elapsedNow().inWholeMilliseconds }

            while (true) {
                val remaining = deadlineMs - elapsed()
                if (remaining <= 0) {
                    return McpVerifyResult.failure(elapsed(), "握手超时（${timeout.toSeconds()}s）")
                }
                val line = try {
                    withTimeoutOrNull(remaining) {
                        runInterruptible { reader.readLine() }
                    } ?: return McpVerifyResult.failure(elapsed(), "握手超时（${timeout.toSeconds()}s）")
                } catch (e: TimeoutCancellationException) {
                    return McpVerifyResult.failure(elapsed(), "握手超时（${timeout.toSeconds()}s）")
                }
                if (line.isNullOrBlank()) continue

                val response = parseJsonLine(line) ?: continue
                val rpcError = rpcErrorMessage(response)
                if (rpcError != null) {
                    return McpVerifyResult.failure(elapsed(), "initialize 被拒绝: $rpcError")
                }
                val result = response["result"] as? JsonObject ?: continue
                return McpVerifyResult.success(elapsed(), serverInfoOf(result))
            }
        } catch (e: Exception) {
            return McpVerifyResult.failure(
                started.elapsedNow().inWholeMilliseconds,
                "进程异常: ${e.message}（exit=${process.exitValueOrNull()}）"
            )
        } finally {
            process.toHandle().destroyForcibly()
        }
    }

    // ==================== JSON-RPC ====================

    /** MCP initialize 请求模型（协议版本 2025-06-18，服务端会协商返回它支持的版本）。 */
    @Serializable
    private data class JsonRpcRequest(
        val jsonrpc: String,
        val id: Int,
        val method: String,
        val params: InitializeParams
    )

    @Serializable
    private data class InitializeParams(
        val protocolVersion: String,
        val capabilities: Map<String, String> = emptyMap(),
        val clientInfo: ClientInfo
    )

    @Serializable
    private data class ClientInfo(val name: String, val version: String)

    private fun initializeRequest(): String =
        json.encodeToString(
            JsonRpcRequest.serializer(),
            JsonRpcRequest(
                jsonrpc = "2.0",
                id = 1,
                method = "initialize",
                params = InitializeParams(
                    protocolVersion = "2025-06-18",
                    clientInfo = ClientInfo(name = "mederi", version = "1.0.0")
                )
            )
        )

    /** 提取 JSON-RPC 错误消息（error 节点存在时），无错返回 null。 */
    private fun rpcErrorMessage(response: JsonObject): String? {
        val error = response["error"] as? JsonObject ?: return null
        return (error["message"] as? JsonPrimitive)?.content ?: error.toString()
    }

    /** 解析单行 JSON；不是对象时返回 null（跳过日志行等噪音）。 */
    private fun parseJsonLine(line: String): JsonObject? = try {
        (json.parseToJsonElement(line) as? JsonObject)
    } catch (e: Exception) {
        null
    }

    /**
     * 在 HTTP 响应体中查找 initialize 的 result 节点。
     * 兼容两种形态：纯 JSON body、SSE（逐行扫描 `data:` 前缀）。
     * 返回 (承载它的响应对象, result 节点)；找不到返回 null。
     */
    private fun findInitializeResult(body: String): Pair<JsonObject, JsonObject>? {
        parseJsonLine(body)?.let { root -> root.resultNode()?.let { return root to it } }
        for (line in body.lineSequence()) {
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            val obj = parseJsonLine(data) ?: continue
            obj.resultNode()?.let { return obj to it }
        }
        return null
    }

    private fun JsonObject.resultNode(): JsonObject? = this["result"] as? JsonObject

    private fun serverInfoOf(result: JsonObject): String? {
        val info = result["serverInfo"] as? JsonObject ?: return null
        val name = (info["name"] as? JsonPrimitive)?.content
        val version = (info["version"] as? JsonPrimitive)?.content
        return listOfNotNull(name, version).joinToString(" ").ifBlank { null }
    }

    /** remote 握手的公共请求骨架（URL + 用户 headers），方法与协议头由调用方补充。 */
    private fun remoteRequestBuilder(remote: McpConnection.Remote): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(remote.url))
            .timeout(timeout)
            .apply { remote.headers.forEach { (k, v) -> header(k, v) } }

    private fun Process.exitValueOrNull(): Int? = try {
        exitValue()
    } catch (e: IllegalThreadStateException) {
        null
    }
}
