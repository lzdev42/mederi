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

    /** 命令执行结果（输出 + exit code 分离，供 ExecuteCommandTool 格式化和 VerifyTools 判定） */
    data class CommandResult(val output: String, val exitCode: Int)

    /**
     * 执行一条 shell 命令，返回 [CommandResult]。
     *
     * ExecuteCommandTool 和 VerifyTools 的自动验证共用此方法——
     * 沙箱包装、工作目录、输出读取线程、超时处理全一致。
     */
    suspend fun runCommand(command: String, timeoutSeconds: Int = 120): CommandResult {
        if (command.isBlank()) return CommandResult("Error: command is empty", -1)

            val wrapped = sandbox?.wrap(command)
                ?: CommandSandbox.WrappedCommand(listOf(sandbox?.shell ?: "sh", "-c", command), null, false)
            val workDir = File(allowedDirectories.first())
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
                return CommandResult("Command timed out after ${timeoutSeconds}s\n$partialOutput", -1)
            }

            val output = outputFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
            val exitCode = process.exitValue()
            val prefixed = if (wrapped.warning != null) wrapped.warning + output else output
            return CommandResult(prefixed, exitCode)
    }

    @Serializable
    data class ExecuteCommandArgs(
        @LLMDescription("要执行的 shell 命令。")
        val command: String = "",
        @LLMDescription("命令超时时间（秒），默认 120 秒。")
        @kotlinx.serialization.SerialName("timeout_seconds")
        val timeoutSeconds: Int = 120
    )

    inner class ExecuteCommandTool : SimpleTool<ExecuteCommandArgs>(
        argsType = typeToken<ExecuteCommandArgs>(),
        name = "execute_command",
        description = "在项目主目录下执行一条 shell 命令并返回标准输出和标准stderr。适用于编译、运行测试、构建、git 操作等。" +
            "写入受沙箱限制：仅项目目录、临时目录与构建缓存可写；需要写其他位置时引导用户把目录加入项目或全局白名单。"
    ) {
        override suspend fun execute(args: ExecuteCommandArgs): String {
            val result = runCommand(args.command, args.timeoutSeconds)
            val exitPrefix = if (result.exitCode != 0) "[exit code: ${result.exitCode}]\n" else ""
            return exitPrefix + result.output
        }
    }
}
