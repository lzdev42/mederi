package xyz.mederi.tools.sandbox

import org.junit.Assume.assumeTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import xyz.mederi.tools.ShellTools
import xyz.mederi.tools.background.BackgroundTaskManager
import xyz.mederi.api.exception.MederiStateException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxCommandSandboxTest {
    private val success = LinuxCommandSandbox.ProbeResult(0, "")

    private fun fixture(block: (File) -> Unit) {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/linux-sandbox-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun sandbox(root: File, project: File = File(root, "project"),
        extra: List<String> = emptyList(), probe: (List<String>) -> LinuxCommandSandbox.ProbeResult = { success }
    ) = LinuxCommandSandbox(listOf(project.path), extra, File(root, "home").path,
        listOf(File(root, "tmp").path), probe)

    @Test
    fun fullProbeAndExecutionUseIdenticalMountsWithoutDeviceMagicLinks() = fixture { root ->
        val calls = mutableListOf<List<String>>()
        val sandbox = sandbox(root, probe = { calls.add(it); success })
        assertTrue(sandbox.status("bash").available)
        val actual = sandbox.wrap(listOf("bash", "-c", "echo payload"))
        assertEquals(listOf("bwrap", "--version"), calls[0])
        assertEquals(calls[1].dropLast(2), actual.dropLast(4))
        assertTrue("--unshare-pid" in actual)
        assertTrue("--die-with-parent" in actual)
        assertTrue(actual.windowed(2).contains(listOf("--dev", "/dev")))
        assertTrue(actual.windowed(2).contains(listOf("--proc", "/proc")))
        assertFalse(actual.any { it.startsWith("/dev/std") || it.startsWith("/private/") })
        assertFalse("--tmpfs" in actual, "host temp files must survive subsequent commands")
        sandbox.wrap(listOf("true"))
        assertEquals(2, calls.size, "probe must run only once per instance")
    }

    @Test
    fun missingCachesAndExplicitRootsAreCreatedWithoutMakingParentsWritable() = fixture { root ->
        val extra = File(root, "extra/missing")
        val sandbox = sandbox(root, extra = listOf(extra.path))
        val argv = sandbox.wrap(listOf("true"))
        val mounts = argv.windowed(3).filter { it[0] == "--bind" }.map { it[1] }
        assertTrue(File(root, "home/.gradle").isDirectory)
        assertTrue(File(root, "home/.cache").isDirectory)
        assertTrue(extra.isDirectory)
        assertTrue(extra.path in mounts)
        assertFalse(root.path in mounts)
        assertFalse(File(root, "home").path in mounts)
        assertFalse(extra.parent in mounts)
    }

    @Test
    fun canonicalAliasesAndNestedRootsDoNotProduceConflictingMounts() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val alias = File(root, "alias")
        Files.createSymbolicLink(alias.toPath(), project.toPath())
        val sandbox = sandbox(root, extra = listOf(alias.path, File(project, "nested").path))
        val mounts = sandbox.wrap(listOf("true")).windowed(3).filter { it[0] == "--bind" }.map { it[1] }
        assertEquals(1, mounts.count { it == project.path })
        assertFalse(alias.path in mounts)
        assertFalse(File(project, "nested").path in mounts)
    }

    @Test
    fun invalidRootsFailBeforeMountingOrExecutingPayload() = fixture { root ->
        for (path in listOf("relative/path", "/", "/proc", "/proc/self/root", "/dev", "/sys")) {
            val sandbox = sandbox(root, extra = listOf(path))
            assertFalse(sandbox.status("sh").available, path)
            assertFailsWith<MederiStateException>(path) { sandbox.wrap(listOf("touch", "PAYLOAD")) }
        }
    }

    @Test
    fun mountPreparationFailureRetainsTheFailingPath() = fixture { root ->
        val file = File(root, "not-a-directory").apply { writeText("unchanged") }
        val sandbox = sandbox(root, extra = listOf(File(file, "child").path))
        assertFalse(sandbox.status("sh").available)
        assertTrue(sandbox.status("sh").detail.contains("not-a-directory"))
        assertFailsWith<MederiStateException> { sandbox.wrap(listOf("touch", "PAYLOAD")) }
        assertEquals("unchanged", file.readText())
    }

    @Test
    fun existingFileWhitelistDoesNotGrantWriteAccessToItsParent() = fixture { root ->
        val file = File(root, "allowed-file").apply { writeText("existing") }
        val sandbox = sandbox(root, extra = listOf(file.path))
        val mounts = sandbox.wrap(listOf("true")).windowed(3).filter { it[0] == "--bind" }.map { it[1] }
        assertTrue(file.path in mounts)
        assertFalse(root.path in mounts)
        assertEquals("existing", file.readText())
        val invalidChild = sandbox(root, extra = listOf(file.path, File(file, "child").path))
        assertFalse(invalidChild.status("sh").available, "file mounts cannot contain child directories")
    }

    @Test
    fun completeProbeFailurePreservesStderrAndNeverFallsBackToShell() = fixture { root ->
        val diagnostic = "bwrap: Can't find source path /example: Permission denied"
        val sandbox = sandbox(root, probe = {
            if (it == listOf("bwrap", "--version")) success
            else LinuxCommandSandbox.ProbeResult(1, diagnostic)
        })
        val status = sandbox.status("bash")
        assertFalse(status.available)
        assertTrue(status.detail.contains(diagnostic))
        val error = assertFailsWith<MederiStateException> { sandbox.wrap(listOf("bash", "-c", "payload")) }
        assertTrue(error.message.orEmpty().contains(diagnostic))
    }

    @Test
    fun unavailableBinaryAndTimeoutAreExplicitFailures() = fixture { root ->
        for (diagnostic in listOf("Cannot run program bwrap: No such file", "探测超过 10 秒")) {
            val calls = mutableListOf<List<String>>()
            val sandbox = sandbox(root, probe = { calls.add(it); LinuxCommandSandbox.ProbeResult(null, diagnostic) })
            assertTrue(sandbox.status("bash").detail.contains(diagnostic))
            assertFailsWith<MederiStateException> { sandbox.wrap(listOf("payload")) }
            assertEquals(1, calls.size)
            assertFalse(File(root, "home/.gradle").exists())
        }
    }

    @Test
    fun failedLinuxSandboxCannotLaunchForegroundOrBackgroundPayloads() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val marker = File(project, "must-not-exist")
        val linux = sandbox(root, probe = { LinuxCommandSandbox.ProbeResult(1, "probe deliberately rejected") })
        val commandSandbox = CommandSandbox(listOf(project.path), linux)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = BackgroundTaskManager(scope)
        val tools = ShellTools(listOf(project.path), commandSandbox, manager, "sandbox-test")
        try {
            val command = "printf escaped > '${marker.path}'"
            val result = runBlocking { tools.runCommand(command) }
            assertEquals(-1, result.exitCode)
            assertTrue(result.output.contains("probe deliberately rejected"))
            val background = runBlocking {
                tools.BashTool().execute(ShellTools.BashArgs(command = command, background = true))
            }
            assertTrue(background.startsWith("Error starting background command:"), background)
            assertTrue(background.contains("probe deliberately rejected"))
            assertEquals(0, manager.runningCountForSession("sandbox-test"))
            assertFalse(marker.exists(), "neither path may fall back to an unsandboxed shell")
        } finally { scope.cancel() }
    }

    /** Linux CI 设置 MEDERI_REQUIRE_LINUX_SANDBOX=1：不允许因缺 bwrap/namespace 权限而假绿。 */
    @Test
    fun realLinuxSandboxEnforcesWritesStreamsAndPidIsolation() = fixture { root ->
        val requireLinux = System.getenv("MEDERI_REQUIRE_LINUX_SANDBOX") == "1"
        val linux = System.getProperty("os.name").lowercase().contains("linux")
        if (requireLinux) assertTrue(linux, "This verification requires a Linux host")
        assumeTrue("real bubblewrap test requires Linux", linux)
        val sandbox = sandbox(root)
        assertTrue(sandbox.status("sh").available, sandbox.status("sh").detail)
        val project = File(root, "project")
        val outside = File(root, "outside.txt").apply { writeText("readable") }
        val hostPidNamespace = Files.readSymbolicLink(File("/proc/self/ns/pid").toPath()).toString()
        fun run(command: String, stdin: String = ""): Pair<Int, String> {
            val process = ProcessBuilder(sandbox.wrap(listOf("sh", "-c", command)))
                .directory(project).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(stdin) }
            val output = java.util.concurrent.FutureTask { process.inputStream.bufferedReader().use { it.readText() } }
            Thread(output).apply { isDaemon = true; start() }
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "sandbox command timed out")
                return process.exitValue() to output.get(2, TimeUnit.SECONDS)
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        assertEquals(0, run("printf success > result.txt").first)
        assertEquals("success", File(project, "result.txt").readText())
        assertTrue(run("printf forbidden > '${outside.path}'").first != 0)
        assertEquals("readable", outside.readText())
        assertEquals(0 to "readable", run("cat '${outside.path}'"))
        assertEquals(0 to "stdin\nstderr\n", run("cat; printf 'stderr\\n' >&2", "stdin\n"))
        assertEquals(0, run("printf cache > '${File(root, "home/.gradle/first").path}'").first)
        assertEquals("cache", File(root, "home/.gradle/first").readText())
        assertEquals(37, run("exit 37").first)
        val (code, namespace) = run("readlink /proc/self/ns/pid")
        assertEquals(0, code)
        assertTrue(namespace.trim() != hostPidNamespace, "sandbox must not expose host PID namespace")
        assertEquals(0, run("printf persisted > '${File(root, "tmp/persisted").path}'").first)
        assertEquals(0 to "persisted", run("cat '${File(root, "tmp/persisted").path}'"))
        val ready = File(project, "child-ready")
        val process = ProcessBuilder(sandbox.wrap(listOf("sh", "-c",
            "sleep 60 & child=\$!; cat /proc/\$child/status > child-status; : > child-ready; wait")))
            .directory(project).redirectErrorStream(true).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!ready.exists() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(ready.exists(), "sandbox child did not start")
            // The outer host PID can be located through the command's process descendants.
            val child = process.toHandle().descendants().use { stream ->
                stream.filter { it.info().command().orElse("").endsWith("/sleep") }.findFirst().orElseThrow()
            }
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            val stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child.isAlive && System.nanoTime() < stopDeadline) Thread.sleep(20)
            assertFalse(child.isAlive, "PID namespace payload survived bwrap termination")
        } finally { if (process.isAlive) process.destroyForcibly() }

    }
}
