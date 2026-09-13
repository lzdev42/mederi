package xyz.mederi.mcp.engine

import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.defaultStdioTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport

/**
 * JVM 实现：ProcessBuilder 起进程（合并父环境变量保证 PATH 可用），
 * 交给 Koog `defaultStdioTransport` 包装成 MCP stdio 传输。
 *
 * MCP SDK 的 StdioClientTransport 只持有流、不持有进程——close 时不会销毁子进程，
 * 这里注册 onClose 兜底回收（先 SIGTERM，2 秒不退再 SIGKILL），避免 stdio server 成孤儿进程。
 */
internal actual fun mcpStdioTransport(command: String, args: List<String>, env: Map<String, String>): Transport {
    val process = ProcessBuilder(listOf(command) + args)
        .apply { environment().putAll(env) }
        .start()
    val transport = McpToolRegistryProvider.defaultStdioTransport(process)
    transport.onClose {
        process.destroy()
        Thread {
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.start()
    }
    return transport
}
