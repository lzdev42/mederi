package xyz.mederi.browser.obscura

import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.defaultStdioTransport
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonObject
import xyz.mederi.mcp.runtime.McpRuntime
import java.util.concurrent.TimeUnit

/**
 * Obscura 浏览器 MCP 会话：每实例一 stdio 子进程，任务级长连接。
 *
 * - 永远 `obscura mcp --stealth`（用户拍板，不可配、无开关）；不启用 OBSCURA_WEB_SEARCH。
 * - 进程模式照 [xyz.mederi.mcp.engine.mcpStdioTransport]：[McpRuntime.environment] 合并父环境、
 *   [ProcessBuilder] 起进程、[McpToolRegistryProvider.defaultStdioTransport] 包装、
 *   onClose SIGTERM→2s→SIGKILL 回收（SDK transport 只持有流不持有进程）。
 * - [close] 幂等：先关 client，再显式 reap 进程（双保险防孤儿进程）。
 */
interface ObscuraMcpSession {
    suspend fun listTools(): List<Tool>
    suspend fun callTool(name: String, arguments: JsonObject): CallToolResult
    suspend fun close()
}

class ObscuraMcpClient(private val binary: String) : ObscuraMcpSession {

    /**
     * 完整命令行。固化 `mcp --stealth --allow-private-network`，暴露供断言。
     *
     * `--allow-private-network` 关闭 obscura 自带的 SSRF 防护（默认拒绝 loopback/RFC1918/链路本地/保留段）：
     * 本机代理 fake-ip 模式下 DNS 对所有域名回答 `198.18.0.0/15` 保留段占位 IP，会被该防护全量拦截，
     * 导致浏览器任务完全不可用。放开后浏览器可正常访问内网/保留段地址（含经代理的流量）。
     */
    val commandLine: List<String> = listOf(binary, "mcp", "--stealth", "--allow-private-network")

    /** 子进程引用，暴露供探针断言进程回收（close 后 !isAlive）。 */
    var process: Process? = null
        private set

    private var client: Client? = null

    /** 起子进程 + MCP 握手。失败抛异常，调用方负责或由 close 兜底 reap。 */
    suspend fun start() {
        val environment = McpRuntime.environment(emptyMap())
        val p = ProcessBuilder(commandLine).apply {
            environment().clear()
            environment().putAll(environment)
        }.start()
        process = p
        val transport = McpToolRegistryProvider.defaultStdioTransport(p)
        transport.onClose { reap(p) }
        val c = Client(clientInfo = Implementation("mederi", "1.0.0"))
        c.connect(transport)
        client = c
    }

    override suspend fun listTools(): List<Tool> =
        requireClient().listTools().tools

    override suspend fun callTool(name: String, arguments: JsonObject): CallToolResult =
        requireClient().callTool(name = name, arguments = arguments)

    /** 幂等：client.close 置 null，再显式 reap 子进程。 */
    override suspend fun close() {
        val c = client
        client = null
        if (c != null) runCatching { c.close() }
        process?.let { reap(it) }
    }

    /** SIGTERM → 2s 不退 → SIGKILL（照抄 McpStdioTransport.jvm.kt onClose 语义）。 */
    private fun reap(p: Process) {
        p.destroy()
        Thread {
            try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.start()
    }

    private fun requireClient(): Client =
        client ?: throw IllegalStateException("ObscuraMcpClient not started; call start() first")
}
