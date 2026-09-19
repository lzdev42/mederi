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
}
