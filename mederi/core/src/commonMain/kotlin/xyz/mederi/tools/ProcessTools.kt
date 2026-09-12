package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import xyz.mederi.tools.sandbox.ProcessRegistry

/**
 * 进程管理工具：管理 mederi 通过 execute_command 启动的进程组。
 *
 * 为什么需要（docs/sandbox-plan.md）：macOS Seatbelt 无 process-signal 操作，沙箱内命令
 * 无法 kill 任何进程（连自己 spawn 的都不行）。list_processes / stop_process 在宿主侧
 * （沙箱外）执行回收，安全边界 = 只能作用于 ProcessRegistry 里 mederi 自己启动的进程组；
 * 查不到的 pid 一律拒绝。沙箱内命令永远无法写入注册表。
 */
class ProcessTools {

    @Serializable
    data class ListProcessesArgs(
        @LLMDescription("按命令文本子串过滤（可选，大小写不敏感）。留空列出全部。")
        val filter: String = ""
    )

    inner class ListProcessesTool : SimpleTool<ListProcessesArgs>(
        argsType = typeToken<ListProcessesArgs>(),
        name = "list_processes",
        description = "列出 mederi 通过 execute_command 启动、当前仍在运行的进程组（pid / 命令 / 工作目录 / 启动时间）。" +
            "适合追踪 dev server、后台任务。输出的 pid 供 stop_process 使用。"
    ) {
        override suspend fun execute(args: ListProcessesArgs): String {
            val entries = ProcessRegistry.list()
            val filtered = if (args.filter.isBlank()) entries
                else entries.filter { it.command.contains(args.filter, ignoreCase = true) }
            if (filtered.isEmpty()) return "No mederi-managed processes running."
            return buildString {
                appendLine("PID\tCOMMAND\tWORKDIR\tSTARTED")
                filtered.forEach {
                    appendLine("${it.pid}\t${it.command.replace('\t', ' ')}\t${it.workDir}\t${it.startedAt}")
                }
            }
        }
    }

    @Serializable
    data class StopProcessArgs(
        @LLMDescription("进程 pid（来自 list_processes 输出）。")
        val pid: Long = 0,
        @LLMDescription("为 true 时若 SIGTERM 未能停止，自动升级为 SIGKILL 强杀。默认 false 只发 SIGTERM。")
        val force: Boolean = false
    )

    inner class StopProcessTool : SimpleTool<StopProcessArgs>(
        argsType = typeToken<StopProcessArgs>(),
        name = "stop_process",
        description = "停止 mederi 通过 execute_command 启动的进程（含其派生的全部子进程）。" +
            "仅能作用于 mederi 自己启动的进程（list_processes 可查）。" +
            "macOS 沙箱内命令无法 kill 任何进程，停止自己起的 dev server / 后台任务必须用本工具。"
    ) {
        override suspend fun execute(args: StopProcessArgs): String {
            if (args.pid <= 0) return "Error: pid must be a positive number from list_processes."
            val entry = ProcessRegistry.get(args.pid)
                ?: return "Error: pid ${args.pid} is not a mederi-managed process (nothing to stop). " +
                    "Only processes started via execute_command and still running are manageable."
            if (!entry.isAlive()) {
                ProcessRegistry.remove(args.pid)
                return "Process ${args.pid} has already exited."
            }
            val action = ProcessRegistry.killGroup(entry.pid, entry.pgid, force = args.force)

            var alive = entry.isAlive()
            var waited = 0L
            while (alive && !args.force && waited < 4000) {
                delay(200)
                waited += 200
                alive = entry.isAlive()
            }
            if (alive && !args.force) {
                return "Warning: sent SIGTERM but the process group is still alive. " +
                    "Retry with force=true to SIGKILL it.\n($action)"
            }
            if (alive) {
                // force 已发 SIGKILL；再给 500ms 让内核回收
                delay(500)
                alive = entry.isAlive()
            }
            if (alive) return "Warning: process group still alive after kill. It may be unkillable (e.g. D state).\n($action)"
            ProcessRegistry.remove(args.pid)
            return "Stopped pid ${args.pid}.\n($action)"
        }
    }
}
