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
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.ByteBuffer
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
    fun preparationDoesNotCreateWritableRootsInsideDeniedOrProtectedDirectories() = fixture { root ->
        val denied = File(root, "denied").apply { mkdirs() }
        val git = File(root, "project/.git").apply { mkdirs() }
        val deniedChild = File(denied, "new-child")
        val protectedChild = File(git, "new-child")
        val runner = LinuxCommandSandbox(emptyList(), listOf(deniedChild.path, protectedChild.path),
            tempDirs = emptyList(), deniedPaths = listOf(denied.path), probe = { success })
        val argv = runner.wrap(listOf("true"))
        assertFalse(deniedChild.exists(), "Preparing an extra writable root must not mutate a denied directory")
        assertFalse(protectedChild.exists(), "Preparing an extra writable root must not mutate protected metadata")
        assertFalse(argv.windowed(3).contains(listOf("--bind", deniedChild.path, deniedChild.path)))
        assertFalse(argv.windowed(3).contains(listOf("--bind", protectedChild.path, protectedChild.path)))
    }

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
    fun explicitRootsAreCreatedWithoutGrantingHomeCachesOrParents() = fixture { root ->
        val extra = File(root, "extra/missing")
        val sandbox = sandbox(root, extra = listOf(extra.path))
        val argv = sandbox.wrap(listOf("true"))
        val mounts = argv.windowed(3).filter { it[0] == "--bind" }.map { it[1] }
        assertFalse(File(root, "home/.gradle").exists())
        assertFalse(File(root, "home/.cache").exists())
        assertTrue("--unshare-net" in argv)
        assertTrue(argv.windowed(2).contains(listOf("--seccomp", "3")))
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
    fun explicitDeniedPathsUseAnonymousReadOnlyMasksAndSurviveFullAccess() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val directory = File(project, "private").apply { mkdirs() }
        val file = File(project, "credentials").apply { writeText("original-secret") }
        val posix = Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")
        val permissions = if (posix) Files.getPosixFilePermissions(file.toPath()) else null
        val sandbox = LinuxCommandSandbox(listOf(project.path), emptyList(), tempDirs = emptyList(),
            probe = { success }, deniedPaths = listOf(directory.path, file.path))
        val restricted = sandbox.wrap(listOf("true"))
        val full = sandbox.wrap(listOf("true"), fullAccess = true)
        for (argv in listOf(restricted, full)) {
            assertTrue(argv.windowed(4).contains(listOf("--perms", "000", "--tmpfs", directory.path)))
            assertTrue(argv.windowed(2).contains(listOf("--remount-ro", directory.path)))
            assertTrue(argv.windowed(5).contains(listOf("--perms", "000", "--ro-bind-data", "MASK_FD", file.path)))
            assertFalse("--file" in argv, "masking must never truncate the real host file")
        }
        assertTrue(full.windowed(3).contains(listOf("--bind", "/", "/")))
        assertFalse("--unshare-net" in full)
        assertFalse("--seccomp" in full)
        assertEquals("original-secret", file.readText())
        if (posix) assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
    }

    @Test
    fun absentDeniedTargetBlocksWithoutCreatingOrIgnoringIt() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val missing = File(project, "not-created/secret")
        val sandbox = LinuxCommandSandbox(listOf(project.path), emptyList(), tempDirs = emptyList(),
            probe = { success }, deniedPaths = listOf(missing.path))
        for (full in listOf(false, true)) {
            val error = assertFailsWith<MederiStateException> { sandbox.wrap(listOf("true"), fullAccess = full) }
            assertTrue(error.message.orEmpty().contains("Linux denied path does not exist"), error.message)
        }
        assertFalse(missing.parentFile.exists())
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
        File(root, "home/.gradle").mkdirs()
        assertTrue(run("printf cache > '${File(root, "home/.gradle/first").path}'").first != 0)
        assertFalse(File(root, "home/.gradle/first").exists())
        assertEquals(37, run("exit 37").first)
        val (code, namespace) = run("readlink /proc/self/ns/pid")
        assertEquals(0, code)
        assertTrue(namespace.trim() != hostPidNamespace, "sandbox must not expose host PID namespace")
        assertEquals(0, run("printf persisted > '${File(root, "tmp/persisted").path}'").first)
        assertEquals(0 to "persisted", run("cat '${File(root, "tmp/persisted").path}'"))
        // 仅网络命名空间不能阻止 pathname Unix socket；seccomp 必须阻止该宿主代理通路。
        val socket = File(root, "s").toPath()
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            val javaExecutable = File(System.getProperty("java.home"), "bin/java").path
            val classpath = listOf(
                SandboxSocketProbe::class.java.protectionDomain.codeSource.location.toURI(),
                Unit::class.java.protectionDomain.codeSource.location.toURI(),
            ).joinToString(File.pathSeparator) { File(it).path }
            val command = listOf(javaExecutable, "-cp", classpath, SandboxSocketProbe::class.java.name, socket.toString())
            fun connect(networkAccess: Boolean): Pair<Int, String> {
                val process = ProcessBuilder(sandbox.wrap(command, networkAccess)).redirectErrorStream(true).start()
                process.outputStream.close()
                val output = java.util.concurrent.FutureTask { process.inputStream.bufferedReader().use { it.readText() } }
                Thread(output).apply { isDaemon = true; start() }
                try {
                    assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Unix socket probe timed out")
                    return process.exitValue() to output.get(2, TimeUnit.SECONDS)
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
            val denied = connect(false)
            assertTrue(denied.first != 0, "restricted payload reached host Unix socket: ${denied.second}")
            val accepted = java.util.concurrent.FutureTask {
                server.accept().use { client ->
                    val bytes = ByteBuffer.allocate(2)
                    while (bytes.hasRemaining()) assertTrue(client.read(bytes) >= 0)
                    String(bytes.array())
                }
            }
            Thread(accepted).apply { isDaemon = true; start() }
            val allowed = connect(true)
            assertEquals(0, allowed.first, allowed.second)
            assertEquals("OK", accepted.get(5, TimeUnit.SECONDS))
        }
        Files.deleteIfExists(socket)
        val ready = File(project, "child-ready")
        val commandSandbox = CommandSandbox(listOf(project.path), sandbox)
        val wrapped = commandSandbox.wrap("sleep 60 & child=\$!; cat /proc/\$child/status > child-status; : > child-ready; wait")
        assertTrue(wrapped.processGroupLeader)
        val process = ProcessBuilder(wrapped.argv)
            .directory(project).redirectErrorStream(true).start()
        ProcessRegistry.register(process.pid(), process.pid(), "sandbox child cleanup test", project.path)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!ready.exists() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(ready.exists(), "sandbox child did not start")
            // The outer host PID can be located through the command's process descendants.
            val child = process.toHandle().descendants().use { stream ->
                stream.filter { it.info().command().orElse("").endsWith("/sleep") }.findFirst().orElseThrow()
            }
            val stat = Files.readString(File("/proc/${process.pid()}/stat").toPath()).substringAfterLast(") ").split(' ')
            assertEquals(process.pid(), stat[2].toLong(), "registered helper must lead its host process group")
            assertTrue(ProcessRegistry.terminate(process.pid(), force = true) != null)
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            val stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child.isAlive && System.nanoTime() < stopDeadline) Thread.sleep(20)
            assertFalse(child.isAlive, "PID namespace payload survived bwrap termination")
        } finally {
            if (process.isAlive) ProcessRegistry.terminate(process.pid(), force = true)
            ProcessRegistry.remove(process.pid())
        }

    }

    @Test
    fun realLinuxDeniedPathsBlockReadWriteAndChmodInBothPermissionModes() = fixture { root ->
        val requireLinux = System.getenv("MEDERI_REQUIRE_LINUX_SANDBOX") == "1"
        val linux = System.getProperty("os.name").lowercase().contains("linux")
        if (requireLinux) assertTrue(linux, "This verification requires a Linux host")
        assumeTrue("real denied-path masking requires Linux", linux)
        val project = File(root, "project").apply { mkdirs() }
        val directory = File(project, "private").apply { mkdirs() }
        val secret = File(directory, "secret").apply { writeText("private-secret") }
        val file = File(project, "credentials").apply { writeText("file-secret") }
        val outside = File(root, "outside").apply { writeText("outside") }
        val permissions = Files.getPosixFilePermissions(file.toPath())
        val sandbox = LinuxCommandSandbox(listOf(project.path), emptyList(), tempDirs = emptyList(),
            deniedPaths = listOf(directory.path, file.path))
        assertTrue(sandbox.status("sh").available, sandbox.status("sh").detail)
        fun run(command: String, full: Boolean): Pair<Int, String> {
            val process = ProcessBuilder(sandbox.wrap(listOf("sh", "-c", command), fullAccess = full))
                .directory(project).redirectErrorStream(true).start()
            process.outputStream.close()
            val output = java.util.concurrent.FutureTask { process.inputStream.bufferedReader().use { it.readText() } }
            Thread(output).apply { isDaemon = true; start() }
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "denied path probe timed out")
                return process.exitValue() to output.get(2, TimeUnit.SECONDS)
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        for (full in listOf(false, true)) {
            for (command in listOf(
                "cat '${file.path}'", "printf replacement > '${file.path}'", "chmod 600 '${file.path}'",
                "cat '${secret.path}'", "printf added > '${File(directory, "new").path}'", "chmod 700 '${directory.path}'",
            )) assertTrue(run(command, full).first != 0, "$command escaped deny mask (full=$full)")
            assertEquals(0, run("printf public > public.txt", full).first)
        }
        assertTrue(run("printf full > '${outside.path}'", false).first != 0)
        assertEquals(0, run("printf full > '${outside.path}'", true).first)
        assertEquals("file-secret", file.readText())
        assertEquals("private-secret", secret.readText())
        assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
        assertFalse(File(directory, "new").exists())
    }
}

/** 独立 JVM payload：验证真实 seccomp 是否阻止宿主 Unix socket，而不是只断言 argv。 */
object SandboxSocketProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(args.single()))
            val bytes = ByteBuffer.wrap("OK".toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
        }
    }
}
