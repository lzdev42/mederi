package xyz.mederi.tools

import kotlinx.coroutines.runBlocking
import xyz.mederi.tools.sandbox.CommandSandbox
import xyz.mederi.tools.sandbox.ProcessRegistry
import xyz.mederi.tools.sandbox.ExecutionPermissions
import xyz.mederi.tools.sandbox.ExecutionPermissionMode
import xyz.mederi.tools.sandbox.SandboxManager
import xyz.mederi.tools.sandbox.SandboxSettings
import java.io.File
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 进程管理端到端测试：明确选择完全访问后起 dev server，验证注册和整组回收。
 * 默认沙盒的网络边界由 CommandSandboxTest 独立验证，不为进程测试放宽默认权限。
 */
class ProcessToolsTest {

    @Test
    fun backgroundServerCanBeListedAndStopped() {
        if (!isMac()) return
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/process-tools-tests").apply { mkdirs() }
        val projectDir = java.nio.file.Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        val port = findFreePort()
        try {
            ProcessRegistry.clear()
            val sandbox = CommandSandbox(listOf(projectDir.absolutePath))
            val manager = SandboxManager()
            runBlocking { manager.updateSettings("project", SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS)) }
            val permissions = ExecutionPermissions(manager, listOf(projectDir.path), "process-tools-test", projectId = "project")
            val shellTools = ShellTools(listOf(projectDir.absolutePath), sandbox, executionPermissions = permissions)

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
            ProcessRegistry.list().filter { it.workDir == projectDir.path }.forEach {
                ProcessRegistry.terminate(it.pid, force = true)
                ProcessRegistry.remove(it.pid)
            }
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
