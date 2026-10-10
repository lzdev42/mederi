package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import java.io.File
import xyz.mederi.tools.sandbox.CommandSandbox
import xyz.mederi.tools.sandbox.ProcessRegistry

/**
 * Shell 命令执行工具集。
 *
 * 命令在项目主目录（第一个目录）下执行，经 [CommandSandbox] 包装：
 * - macOS Seatbelt / Linux bubblewrap：写仅限白名单（项目目录/临时/构建缓存），读全盘
 * - Windows / 未装 bwrap：裸跑 + 警告前缀
 * - shell 探测链：bash → sh（Windows: bash.exe → cmd /c）
 *
 * 无权限弹窗（已拆除）：沙箱 + diff 追踪 + git 是安全网，不再逐操作询问。
 *
 * @param allowedDirectories 项目目录列表（绝对路径），命令的工作目录为第一个目录。
 * @param sandbox 命令沙箱（每 turn 由 ToolFactory 新建）。
 */
class ShellTools(
    private val allowedDirectories: List<String>,
    private val sandbox: CommandSandbox? = null
) {

    /**
     * 命令执行结果（输出 + exit code + 超时标志，供 ExecuteCommandTool 格式化和 VerifyTools 判定）。
     *
     * [timedOut] 为 true 表示进程未在 [ShellTools.runCommand] 的 timeoutSeconds 内完成被强制回收——
     * 与「进程正常退出但 exitCode 非零」的真实失败区分开（VerifyTools 三态判定依赖此标志）。
     */
    data class CommandResult(val output: String, val exitCode: Int, val timedOut: Boolean = false)

    /**
     * 执行一条 shell 命令，返回 [CommandResult]。
     *
     * ExecuteCommandTool 和 VerifyTools 的自动验证共用此方法——
     * 沙箱包装、工作目录、输出读取线程、超时处理全一致。
     *
     * @param cwd 命令工作目录（绝对路径）；null 或空白时回退到项目主目录（allowedDirectories.first()）。
     */
    suspend fun runCommand(command: String, timeoutSeconds: Int = 120, cwd: String? = null): CommandResult {
        if (command.isBlank()) return CommandResult("Error: command is empty", -1)

            val wrapped = sandbox?.wrap(command)
                ?: CommandSandbox.WrappedCommand(listOf(sandbox?.shell ?: "sh", "-c", command), null, false)
            val workDir = cwd?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(allowedDirectories.first())
            val builder = ProcessBuilder(wrapped.argv)
            builder.directory(workDir)
            builder.redirectErrorStream(true)

            val process = builder.start()

            // 启动即登记：posix 下直接子进程是进程组长（pid==pgid），
            // 宿主侧据此整组回收（stop_process / 超时清理），安全边界见 ProcessRegistry。
            ProcessRegistry.register(
                pid = process.pid(),
                pgid = if (wrapped.processGroupLeader) process.pid() else null,
                command = command,
                workDir = workDir.absolutePath
            )

            val outputFuture = java.util.concurrent.FutureTask {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            Thread(outputFuture).start()

            val completed = process.waitFor(timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)

            if (!completed) {
                // 超时整组回收：仅 destroyForcibly 只杀直接子进程，后台孙进程会变孤儿继续跑
                if (wrapped.processGroupLeader) {
                    ProcessRegistry.killGroup(process.pid(), process.pid(), force = true)
                }
                process.destroyForcibly()
                outputFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
                val partialOutput = outputFuture.get()
                return CommandResult("Command timed out after ${timeoutSeconds}s\n$partialOutput", -1, timedOut = true)
            }

            val output = outputFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
            val exitCode = process.exitValue()
            val prefixed = if (wrapped.warning != null) wrapped.warning + output else output
            return CommandResult(prefixed, exitCode)
    }

    @Serializable
    data class ExecuteCommandArgs(
        @LLMDescription("Shell command to execute.")
        val command: String = "",
        @LLMDescription("Working directory (absolute or project-relative path). Empty = project root.")
        val cwd: String = "",
        @LLMDescription("Timeout in seconds; default 120.")
        @kotlinx.serialization.SerialName("timeout_seconds")
        val timeoutSeconds: Int = 120
    )

    inner class ExecuteCommandTool : SimpleTool<ExecuteCommandArgs>(
        argsType = typeToken<ExecuteCommandArgs>(),
        name = "execute_command",
        description = "Execute a shell command in the given working directory (empty cwd = project root) and return its stdout and stderr. Writes are sandbox-limited: project directories, temp dirs, and build caches only."
    ) {
        override suspend fun execute(args: ExecuteCommandArgs): String {
            val result = runCommand(args.command, args.timeoutSeconds, args.cwd.takeIf { it.isNotBlank() })
            val exitPrefix = if (result.exitCode != 0) "[exit code: ${result.exitCode}]\n" else ""
            return exitPrefix + result.output
        }
    }

    @Serializable
    data class WebSearchArgs(
        @LLMDescription("URL to fetch via curl HTTP GET (http or https only). The tool fetches page content read-only — no POST/PUT/DELETE, no file writes.")
        val url: String = "",
        @LLMDescription("Maximum characters to return; default 8000. Truncates response to fit context.")
        @kotlinx.serialization.SerialName("max_chars")
        val maxChars: Int = 8000,
        @LLMDescription("Timeout in seconds; default 30.")
        @kotlinx.serialization.SerialName("timeout_seconds")
        val timeoutSeconds: Int = 30
    )

    /**
     * 只读网络搜索工具（仅注册给 RESEARCHER 子代理）。
     *
     * 底层经 curl HTTP GET 拉取 URL 内容——只读、无写、无通用 shell。
     * 安全约束：
     * - URL 必须为 http/https 协议（拒绝 file://、ftp:// 等）
     * - URL 不得包含单引号（防止 shell 注入——单引号内 shell 不展开元字符）
     * - curl 命令用 -- 终止选项解析，URL 以单引号包裹
     * - 不传 -o（不写文件）、不传 -d/-X（不发数据、不改方法）
     *
     * 复用 [ShellTools.runCommand]：沙箱包装、进程注册、超时处理全一致。
     */
    inner class WebSearchTool : SimpleTool<WebSearchArgs>(
        argsType = typeToken<WebSearchArgs>(),
        name = "web_search",
        description = "Fetch a URL via curl HTTP GET and return the page content. Read-only: no POST/PUT/DELETE, " +
            "no file writes. Restricted to curl GET only. RESEARCHER subagent only — use this to search the web " +
            "for up-to-date information: documentation, search engine results, API references, release notes, etc."
    ) {
        override suspend fun execute(args: WebSearchArgs): String {
            val url = args.url.trim()
            if (url.isEmpty()) return "Error: url must not be empty."

            // 协议白名单：只允许 http/https（拒绝 file://、ftp://、data: 等）
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return "Error: url must start with http:// or https://"
            }

            // 安全：拒绝含单引号的 URL（单引号是 shell 引用的唯一危险字符）
            if (url.contains("'")) {
                return "Error: url must not contain single quotes."
            }

            // 构造 curl GET 命令：-s 静默、-L 跟随重定向、--max-time 超时、-- 终止选项
            val command = "curl -sL --max-time ${args.timeoutSeconds} -- '$url'"
            val result = runCommand(command, args.timeoutSeconds + 5)

            val exitPrefix = if (result.exitCode != 0) "[exit code: ${result.exitCode}]\n" else ""

            val raw = exitPrefix + result.output
            val maxChars = args.maxChars.coerceAtLeast(100)

            return if (raw.length > maxChars) {
                raw.take(maxChars) + "\n... [truncated, ${raw.length - maxChars} chars omitted]"
            } else {
                raw
            }
        }
    }
}
