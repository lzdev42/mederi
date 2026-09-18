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
        .apply { environment().putAll(mergeEnv(env, System.getenv("PATH").orEmpty())) }
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

/**
 * stdio 子进程环境变量合并。
 *
 * 关键：macOS GUI 应用（Finder 启动）继承的 PATH 只有 `/usr/bin:/bin:/usr/sbin:/sbin`，
 * **不含 `/usr/local/bin`（Homebrew node/npx 所在）** —— 直接继承父 PATH 时
 * `npx -y mcp-remote` 这类 stdio server 会因找不到 node 启动失败（实测
 * "env: node: No such file or directory"）。这里把常见 node 安装目录前缀到 PATH，
 * 保证 stdio 子进程能解析 npx/node。调用方显式传入的 env 优先，其 PATH 不被覆盖。
 */
internal fun mergeEnv(userEnv: Map<String, String>, parentPath: String): Map<String, String> {
    if (userEnv.containsKey("PATH")) return userEnv
    val home = System.getProperty("user.home").orEmpty()
    val candidates = listOf(
        "/usr/local/bin",       // Intel Homebrew / 手动安装
        "/opt/homebrew/bin",    // Apple Silicon Homebrew
        "$home/.volta/bin",     // volta
        "$home/.local/share/fnm", // fnm
        "$home/.nvm/versions/node" // nvm（node 实际在版本子目录，bin 无法直达；保留作冗余无妨）
    )
    val extra = candidates.filter { java.io.File(it).isDirectory }
        .joinToString(":")
    if (extra.isEmpty()) return userEnv
    val merged = LinkedHashMap(userEnv)
    merged["PATH"] = if (parentPath.isBlank()) extra else "$extra:$parentPath"
    return merged
}
