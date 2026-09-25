package xyz.mederi.tools

import kotlinx.coroutines.runBlocking
import xyz.mederi.tools.sandbox.CommandSandbox
import xyz.mederi.tools.sandbox.ProcessRegistry
import java.io.File
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 进程管理端到端测试（macOS Seatbelt 实测链路）：
 * execute_command 沙箱内后台起 dev server → list_processes 可见 → stop_process 沙箱外整组回收。
 *
 * 这正是本次要修的真实场景：沙箱内命令互杀必失败（Operation not permitted），
 * 只有宿主侧 stop_process 能回收。测试跳过非 macOS 平台。
 */
class ProcessToolsTest {

    @Test
    fun backgroundServerCanBeListedAndStopped() {
        if (!isMac()) return
        val projectDir = File(System.getProperty("java.io.tmpdir"), "mdri-proctest-${System.nanoTime()}").apply { mkdirs() }
        val port = findFreePort()
        try {
            ProcessRegistry.clear()
            val sandbox = CommandSandbox(listOf(projectDir.absolutePath))
            val shellTools = ShellTools(listOf(projectDir.absolutePath), sandbox)

            // 沙箱内后台起 server：command 立即返回，python 后台进程留在进程组里
            val start = runBlocking {
                shellTools.runCommand("python3 -m http.server $port > server.log 2>&1 & echo started", timeoutSeconds = 30)
            }
            assertTrue(start.exitCode == 0, "server start command failed: ${start.output}")
            assertTrue(waitUntil(100) { portOpen(port) }, "server did not come up on port $port")

            // list_processes 应能看到（filter 命中）
            val listOut = runBlocking { ProcessTools().ListProcessesTool().execute(ProcessTools.ListProcessesArgs("http.server")) }
            assertTrue(listOut.contains("$port"), "list_processes missed the server:\n$listOut")

            // 拿到 pid 并 stop
            val pid = ProcessRegistry.list().first { it.command.contains("http.server") }.pid
            val stopOut = runBlocking { ProcessTools().StopProcessTool().execute(ProcessTools.StopProcessArgs(pid = pid)) }
            assertTrue(stopOut.contains("Stopped"), "stop_process unexpected:\n$stopOut")

            // 端口应释放、注册表剔除
            assertTrue(waitUntil(100) { !portOpen(port) }, "port $port still open after stop_process")
            assertTrue(ProcessRegistry.list().none { it.pid == pid }, "stopped process still in registry")
        } finally {
            projectDir.deleteRecursively()
        }
    }

    private fun isMac(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun portOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress("127.0.0.1", port), 200) }
        true
    }.getOrDefault(false)

    private fun waitUntil(attempts: Int, block: () -> Boolean): Boolean {
        repeat(attempts) {
            if (block()) return true
            Thread.sleep(50)
        }
        return false
    }
}
