package xyz.mederi.mcp.engine

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.DefaultMcpToolDescriptorParser
import ai.koog.agents.mcp.McpToolDescriptorParser
import ai.koog.agents.mcp.metadata.McpMetadataKeys
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.headers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import xyz.mederi.debug.DebugLog
import xyz.mederi.mcp.servers.domain.McpConnection
import xyz.mederi.mcp.servers.domain.McpDiscoveryResult
import xyz.mederi.mcp.servers.domain.McpServerConfig
import xyz.mederi.mcp.servers.domain.McpToolInfo
import xyz.mederi.mcp.servers.domain.McpVerifyResult
import xyz.mederi.store.McpServersStore
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/**
 * 内核 MCP 引擎：mederi 与已安装 MCP server 的唯一连接入口。
 *
 * 基于 Koog `agents-mcp`（`McpToolRegistryProvider`）+ MCP Kotlin SDK，负责三件事：
 *
 * 1. 把 [McpConnection]（config.db 里的结构化配置）翻译成 MCP SDK 的 [Transport]
 *    - stdio → jvmMain 桥起进程 → Koog `defaultStdioTransport`
 *    - remote sse → `SseClientTransport`（headers 注入 ktor client）
 *    - remote streamable-http（默认，含未标注 type）→ `mcpStreamableHttpTransport`
 * 2. 按 server 名给每个 MCP 工具加前缀命名空间（`serverName_toolName`），防止多 server
 *    工具名冲突、也防止与 mederi 内置工具名撞车。
 * 3. 生命周期：每次使用现连现断（[discover] / [verify] 用后即关；[openSession] 为单个
 *    turn 持有连接，turn 结束由调用方 [McpSession.close]）。
 */
class McpConnector(
    private val store: McpServersStore,
    private val clientName: String = "mederi",
    private val clientVersion: String = "1.0.0",
    private val timeout: Duration = 20.seconds
) {

    // ==================== 公共 API ====================

    /**
     * 发现：连接单个 server，拉取它提供的全部工具（带 server 名前缀）。
     * 连接失败不抛异常，返回 [McpDiscoveryResult.error]。
     */
    suspend fun discover(serverName: String): McpDiscoveryResult {
        val config = store.get(serverName)
            ?: return McpDiscoveryResult(ok = false, error = "MCP server not found: $serverName")
        return discover(config)
    }

    /** 同上，直接传配置。 */
    suspend fun discover(config: McpServerConfig): McpDiscoveryResult {
        val started = TimeSource.Monotonic.markNow()
        val server = try {
            connect(config.connection, config.name)
        } catch (e: Exception) {
            return McpDiscoveryResult(ok = false, error = describe(config, e))
        }
        return try {
            McpDiscoveryResult(
                ok = true,
                tools = server.tools.map { McpToolInfo(name = it.name, description = it.descriptor.description) }
            )
        } catch (e: Exception) {
            McpDiscoveryResult(ok = false, error = "工具发现失败: ${e.message}")
        } finally {
            server.close()
        }
    }

    /**
     * 可用性检查：connect + initialize + listTools 计数后关闭。
     * 不校验 API key 的业务有效性——语义就是"协议层活着"。
     */
    suspend fun verify(connection: McpConnection): McpVerifyResult {
        val started = TimeSource.Monotonic.markNow()
        val server = try {
            connect(connection, "verify")
        } catch (e: Exception) {
            return McpVerifyResult.failure(started.elapsedNow().inWholeMilliseconds, describe(connection, e))
        }
        return try {
            McpVerifyResult.success(
                latencyMs = started.elapsedNow().inWholeMilliseconds,
                serverInfo = server.client.serverVersion
                    ?.let { listOfNotNull(it.name, it.version).joinToString(" ").ifBlank { null } },
                toolCount = server.tools.size
            )
        } catch (e: Exception) {
            McpVerifyResult.failure(started.elapsedNow().inWholeMilliseconds, "工具发现失败: ${e.message}")
        } finally {
            server.close()
        }
    }

    /**
     * 打开一个长连接会话：连接全部已启用的 server，收集它们的工具。
     * 单个 server 失败只记日志跳过（不拖垮整个 turn）；返回的 [McpSession] 由调用方负责 [McpSession.close]。
     */
    suspend fun openSession(): McpSession {
        val configs = store.list().filter { it.enabled }
        if (configs.isEmpty()) return McpSession(emptyList(), emptyList())

        val results = coroutineScope {
            configs.map { config ->
                async {
                    try {
                        connect(config.connection, config.name)
                    } catch (e: Exception) {
                        DebugLog.error("McpConnector", "连接 MCP server ${config.name} 失败: ${describe(config, e)}", e)
                        null
                    }
                }
            }.awaitAll()
        }

        val servers = results.filterNotNull()
        val tools = servers.flatMap { it.tools }
        if (servers.isEmpty()) DebugLog.event("McpConnector", "openSession: 无可用 MCP server")
        else DebugLog.event("McpConnector", "openSession: ${servers.size} server, ${tools.size} tools")
        return McpSession(tools, servers.map { server -> { server.close() } })
    }

    // ==================== 连接 ====================

    /** 建立单条连接 + 发现工具；失败抛异常（由调用方转成错误信息）。 */
    private suspend fun connect(connection: McpConnection, serverName: String): ConnectedServer {
        val transport = createTransport(connection)
        val client = Client(clientInfo = Implementation(clientName, clientVersion))
        // 显式 connect：listTools 需要已建立的连接
        client.connect(transport)
        val parser = PrefixedMcpToolDescriptorParser(serverName)

        // 手动构建 ToolRegistry（而非 koog fromClient）：
        // - 描述符名字带 server 前缀（LLM 侧防冲突）
        // - 工具用 MederiMcpTool：server 调用回落原始工具名（metadata 里存 ToolId）
        val sdkTools = client.listTools().tools
        val registry = ToolRegistry {
            sdkTools.forEach { sdkTool ->
                try {
                    val descriptor = parser.parse(sdkTool)
                    tool(
                        MederiMcpTool(
                            mcpClient = client,
                            serverToolName = sdkTool.name,
                            descriptor = descriptor,
                            metadata = mapOf(McpMetadataKeys.ToolId to sdkTool.name)
                        )
                    )
                } catch (e: Throwable) {
                    DebugLog.error("McpConnector", "[$serverName] MCP 工具 ${sdkTool.name} 描述符解析失败: ${e.message}", e)
                }
            }
        }
        return ConnectedServer(serverName, client, registry.tools)
    }

    // ==================== 传输 ====================

    private fun createTransport(connection: McpConnection): Transport = when (connection) {
        is McpConnection.Stdio -> mcpStdioTransport(connection.command, connection.args, connection.env)
        is McpConnection.Remote -> createRemoteTransport(connection)
    }

    private fun createRemoteTransport(remote: McpConnection.Remote): Transport {
        val httpClient = HttpClient {
            install(SSE)
            defaultRequest {
                remote.headers.forEach { (k, v) -> headers.append(k, v) }
            }
        }
        val transport = when (remote.transportType) {
            // 显式标注 sse → 传统 SSE 传输（GET 建连 + 消息全走 SSE）
            "sse" -> SseClientTransport(
                client = httpClient,
                urlString = remote.url,
                reconnectionTime = timeout,
                requestBuilder = {}
            )
            // 未标注或 streamable-http → 现代 streamable HTTP（POST + SSE 回读）
            else -> httpClient.mcpStreamableHttpTransport(remote.url)
        }
        // MCP SDK 的 transport close 不释放 ktor client——注册 onClose 兜底回收，
        // 避免每次连接泄漏一个 HttpClient。
        transport.onClose { runCatching { httpClient.close() } }
        return transport
    }

    // ==================== 内部 ====================

    private fun describe(config: McpServerConfig, e: Exception): String = describe(config.connection, e)

    private fun describe(connection: McpConnection, e: Exception): String {
        val target = when (connection) {
            is McpConnection.Stdio -> listOf(connection.command) + connection.args
            is McpConnection.Remote -> connection.url
        }
        return "连接失败（$target）: ${e.message ?: e.javaClass.simpleName}"
    }

    // ==================== 结果类型 ====================

    private class ConnectedServer(
        val name: String,
        val client: Client,
        val tools: List<ToolBase<*, *>>
    ) {
        suspend fun close() {
            runCatching { client.close() }
                .onFailure { DebugLog.error("McpConnector", "关闭 MCP 连接 $name 失败: ${it.message}", it) }
        }
    }
}

/**
 * 一次 [McpConnector.openSession] 的产物：已连接 server 的工具 + 待关闭的连接。
 * turn 结束后必须 [close]，释放 stdio 进程 / ktor 客户端。
 */
class McpSession internal constructor(
    val tools: List<ToolBase<*, *>>,
    private val closeActions: List<suspend () -> Unit>
) {
    suspend fun close() = closeActions.forEach { it() }
}

/**
 * 给 MCP 工具名加 server 前缀的解析器（`serverName_toolName`），避免与 mederi 内置工具
 * 及其他 server 的工具撞名。底层委托 Koog 默认解析器做 schema 转换。
 */
private class PrefixedMcpToolDescriptorParser(
    private val serverName: String
) : McpToolDescriptorParser {

    private val delegate: McpToolDescriptorParser = DefaultMcpToolDescriptorParser
    private val prefix: String = serverName
        .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        .joinToString("") + "_"

    override fun parse(tool: Tool): ToolDescriptor =
        delegate.parse(tool).copy(name = "$prefix${tool.name}")
}
