package xyz.mederi.tools.sandbox

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程注册表：记录 mederi 通过 execute_command 启动的进程组。
 *
 * 背景（docs/sandbox-plan.md）：macOS Seatbelt 无 `process-signal` 操作（macOS 26.6.2
 * 实测 profile 编译报 unbound variable），沙箱内进程之间、乃至父对子都无法发信号——
 * 沙箱内 `kill` 一律 "Operation not permitted"。这条限制改不了 profile（没有对应放行项），
 * 因此正确姿势是宿主侧管理：CommandSandbox 用组长包装使每条命令成为独立进程组
 * （posix 下直接子进程 pid == pgid），启动即注册；`list_processes` / `stop_process`
 * 在沙箱外按注册表定向回收。
 *
 * 安全边界：**只杀 mederi 自己 spawn 的进程组**。沙箱内命令永远无法写入本注册表；
 * 注册只发生在 ShellTools.runCommand 的进程启动点（宿主侧）。注册表查不到 pid → 拒绝，
 * AI 无法用它去杀任何非 mederi 启动的进程。
 *
 * 语义：注册表 = "mederi 当前管理中的进程组"。list() 惰性剔除已死的组；正常退出、
 * 超时回收、stop_process 后都从表里消失（惰性或显式 remove）。
 */
object ProcessRegistry {

    data class Entry(
        /** 直接子进程 pid；posix 下同时是进程组长 pid（== pgid） */
        val pid: Long,
        /** posix 下 = pid；Windows 无进程组为 null */
        val pgid: Long?,
        val command: String,
        val workDir: String,
        val startedAt: String = Instant.now().toString(),
        val platform: String = detectPlatform()
    ) {
        /** 进程组（或单进程）是否存活 */
        fun isAlive(): Boolean = isAlive(pid, pgid, platform)
    }

    private val entries = ConcurrentHashMap<Long, Entry>()

    private val isWindowsOs = System.getProperty("os.name").lowercase().contains("win")

    /** 启动点注册：pid <= 0 直接忽略（无意义的句柄）。O(1)，不做任何探测。 */
    fun register(pid: Long, pgid: Long?, command: String, workDir: String) {
        if (pid <= 0) return
        entries[pid] = Entry(pid = pid, pgid = pgid, command = command, workDir = workDir)
    }

    fun get(pid: Long): Entry? = entries[pid]

    /**
     * 列出管理中（存活）的进程组，按启动时间倒序。
     * 惰性剔除已死的组，保证返回的都还活着、内存有界。
     */
    fun list(): List<Entry> {
        val alive = entries.entries.filter { it.value.isAlive() }.map { it.value }
        // 剔除已死的（与上面的遍历分开，避免并发下改坏 map）
        entries.keys.retainAll { key -> alive.any { it.pid == key } }
        return alive.sortedByDescending { it.startedAt }
    }

    fun remove(pid: Long) {
        entries.remove(pid)
    }

    fun clear() {
        entries.clear()
    }

    /** 存活探测：进程组活着（任一组员在）即视为存活。 */
    fun isAlive(pid: Long, pgid: Long?, platform: String = detectPlatform()): Boolean = when {
        platform == "windows" -> false // Windows 不做 kill -0 探测，统一由 tasklist 判定；简化：存疑时当已死
        pgid != null -> runProcess(listOf("kill", "-0", "--", "-$pgid")) == 0
        else -> runProcess(listOf("kill", "-0", pid.toString())) == 0
    }

    /**
     * 终止进程组：TERM 优雅退出，force 时 SIGKILL。
     * 在宿主（沙箱外）执行——沙箱内进程无法发信号，宿主可以。
     * 返回操作描述文本（供工具回显）。
     */
    fun killGroup(pid: Long, pgid: Long?, force: Boolean = false): String {
        return if (isWindowsOs) {
            val args = mutableListOf("taskkill", "/PID", pid.toString(), "/T")
            if (force) args.add("/F")
            val rc = runProcess(args)
            "taskkill /PID $pid /T${if (force) " /F" else ""} rc=$rc"
        } else if (pgid != null) {
            val sig = if (force) "-KILL" else "-TERM"
            val rc = runProcess(listOf("kill", sig, "--", "-$pgid"))
            "kill $sig -- -$pgid rc=$rc"
        } else {
            val sig = if (force) "-KILL" else "-TERM"
            val rc = runProcess(listOf("kill", sig, pid.toString()))
            "kill $sig $pid rc=$rc"
        }
    }

    /** 按已注册条目终止（stop_process 用），找不到返回 null。 */
    fun terminate(pid: Long, force: Boolean = false): String? {
        val entry = get(pid) ?: return null
        return killGroup(entry.pid, entry.pgid, force)
    }

    private fun runProcess(argv: List<String>): Int = runCatching {
        ProcessBuilder(argv).redirectErrorStream(true).start().waitFor()
    }.getOrDefault(-1)

    private fun detectPlatform(): String =
        if (System.getProperty("os.name").lowercase().contains("win")) "windows" else "posix"
}
