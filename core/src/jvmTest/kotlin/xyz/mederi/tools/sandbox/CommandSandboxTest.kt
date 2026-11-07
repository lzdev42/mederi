package xyz.mederi.tools.sandbox

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import xyz.mederi.api.exception.MederiStateException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandSandboxTest {
    private fun fixture(block: (File) -> Unit) {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/command-sandbox-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test
    fun seatbeltUsesTrustedInlineProfileAndEscapesPathsWithoutImplicitCaches() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        File(project, ".git").mkdirs()
        val sandbox = CommandSandbox(listOf(project.path), emptyList(), "Mac OS X")
        val profile = sandbox.seatbeltProfile()
        assertTrue(profile.contains("(subpath ${CommandSandbox.sbplString(project.path)})"))
        assertEquals("\"quoted\\\"project\"", CommandSandbox.sbplString("quoted\"project"))
        assertTrue(profile.contains("(deny file-write* (subpath"))
        assertFalse(profile.contains("(allow network*)"))
        assertFalse(profile.contains(".gradle"))
        assertFalse(profile.contains("(subpath \"/private/var/folders\")"))
        assertTrue(sandbox.seatbeltProfile(networkAccess = true).contains("(allow network*)"))
        assertTrue(sandbox.seatbeltProfile(networkAccess = true).contains("(deny file-write* (subpath"))
        assertFailsWith<IllegalArgumentException> { CommandSandbox.sbplString("bad\npath") }
    }

    @Test
    fun windowsCannotSilentlyExecuteRestrictedCommands() {
        val sandbox = CommandSandbox(emptyList(), emptyList(), "Windows 11")
        assertFalse(sandbox.status().available)
        assertFailsWith<MederiStateException> { sandbox.wrap("echo payload") }
        val approved = sandbox.wrap("echo payload", unsandboxed = true)
        val shellFlag = if (sandbox.shell == "cmd") "/c" else "-c"
        assertEquals(listOf(sandbox.shell, shellFlag, "echo payload"), approved.argv)
        assertFalse(approved.processGroupLeader)
        val withDeny = CommandSandbox(emptyList(), emptyList(), "Windows 11", deniedPaths = listOf("/blocked"))
        assertFailsWith<MederiStateException> { withDeny.wrap("echo payload", unsandboxed = true) }
    }

    @Test
    fun explicitDenyRemainsInFullAccessProfileWithoutRetainingOrdinaryWriteRestrictions() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        File(project, ".git").mkdirs()
        val denied = File(project, "secret").apply { mkdirs() }
        val sandbox = CommandSandbox(listOf(project.path), emptyList(), "Mac OS X", deniedPaths = listOf(denied.path))
        val escaped = CommandSandbox.sbplString(denied.path)
        for (full in listOf(false, true)) {
            val profile = sandbox.seatbeltProfile(fullAccess = full)
            assertTrue(profile.contains("(deny file-read* (subpath $escaped))"))
            assertTrue(profile.contains("(deny file-write* (subpath $escaped))"))
            if (full) {
                assertTrue(profile.contains("(allow default)"))
                assertFalse(profile.contains(".git"), "full access only retains explicit user deny rules")
            }
        }
    }

    @Test
    fun networkGrantKeepsLinuxFilesystemAndProtectedMounts() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val git = File(project, ".git").apply { mkdirs() }
        val calls = mutableListOf<List<String>>()
        val sandbox = LinuxCommandSandbox(listOf(project.path), emptyList(), tempDirs = emptyList(), probe = {
            calls.add(it)
            LinuxCommandSandbox.ProbeResult(0, "")
        })
        val restricted = sandbox.wrap(listOf("true"))
        val network = sandbox.wrap(listOf("true"), networkAccess = true)
        assertTrue("--unshare-net" in restricted)
        assertTrue("--seccomp" in restricted)
        assertFalse("--unshare-net" in network)
        assertFalse("--seccomp" in network)
        assertTrue(network.windowed(3).contains(listOf("--ro-bind", "/", "/")))
        assertTrue(network.windowed(3).contains(listOf("--ro-bind", git.path, git.path)))
        assertEquals(4, calls.size, "each effective policy must pass its complete probe")
    }

    @Test
    fun seccompFilterRejectsNetworkSyscallsAndCompatAbiButAllowsStdio() {
        for ((architecture, auditArch, denied) in listOf(
            Triple("x86_64", 0xc000003e.toInt(), listOf(42, 44, 46, 307, 425)),
            Triple("aarch64", 0xc00000b7.toInt(), listOf(203, 206, 211, 269, 425)),
        )) {
            val escaped = LinuxCommandSandbox.seccompProgram(architecture)
            val bytes = escaped.chunked(5).map { it.removePrefix("\\0").toInt(8).toByte() }.toByteArray()
            fun evaluate(syscall: Int, arch: Int = auditArch): Int {
                val filter = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                var instruction = 0
                var accumulator = 0
                repeat(100) {
                    val offset = instruction * 8
                    val code = filter.getShort(offset).toInt() and 0xffff
                    val jt = filter.get(offset + 2).toInt() and 255
                    val jf = filter.get(offset + 3).toInt() and 255
                    val value = filter.getInt(offset + 4)
                    when (code) {
                        0x20 -> { accumulator = if (value == 4) arch else syscall; instruction++ }
                        0x15 -> instruction += 1 + if (accumulator == value) jt else jf
                        0x45 -> instruction += 1 + if (accumulator and value != 0) jt else jf
                        0x06 -> return value
                        else -> error("Unknown BPF instruction $code")
                    }
                }
                error("BPF did not terminate")
            }
            denied.forEach { assertEquals(0x00050001, evaluate(it), "$architecture syscall=$it") }
            assertEquals(0x7fff0000, evaluate(1), "ordinary write must work")
            assertEquals(0x80000000.toInt(), evaluate(1, 0))
            assertEquals(0x80000000.toInt(), evaluate(42 or 0x40000000))
        }
        assertFailsWith<IllegalArgumentException> { LinuxCommandSandbox.seccompProgram("unknown") }
    }

    @Test
    fun seccompLauncherUsesAnonymousPipeAndPreservesPayloadStdin() = fixture { root ->
        assumeTrue("seccomp launcher verification requires a POSIX /bin/sh", File("/bin/sh").canExecute())
        val output = File(root, "filter")
        val escaped = LinuxCommandSandbox.seccompProgram("x86_64")
        val process = ProcessBuilder("/bin/sh", "-c", LinuxCommandSandbox.SECCOMP_LAUNCHER,
            "test", escaped, "/bin/sh", "-c", "cat <&3 > \"\$1\"; cat", "test", output.path)
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write("original stdin") }
        val result = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), result)
        assertEquals("original stdin", result)
        val expected = escaped.chunked(5).map { it.removePrefix("\\0").toInt(8).toByte() }.toByteArray()
        assertTrue(expected.contentEquals(output.readBytes()))
    }

    /** opt-in 本机验收不能因 sandbox-exec 不可用而假绿。 */
    @Test
    fun realMacSandboxEnforcesWritesProtectedPathsAndNetworkGrants() = fixture { root ->
        val required = System.getenv("MEDERI_REQUIRE_MACOS_SANDBOX") == "1"
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        if (required) assertTrue(mac, "This verification requires a macOS host")
        assumeTrue("native Seatbelt verification requires macOS", mac)
        val project = File(root, "project").apply { mkdirs() }
        val git = File(project, ".git").apply { mkdirs() }
        val config = File(git, "config").apply { writeText("keep") }
        val outside = File(root, "outside").apply { writeText("readable") }
        val sandbox = CommandSandbox(listOf(project.path), emptyList())
        if (required) assertTrue(sandbox.status().available, sandbox.status().detail)
        assumeTrue(sandbox.status().detail, sandbox.status().available)
        fun run(command: String, network: Boolean = false, runner: CommandSandbox = sandbox, full: Boolean = false): Pair<Int, String> {
            val process = ProcessBuilder(runner.wrap(command, networkAccess = network, unsandboxed = full).argv)
                .directory(project).redirectErrorStream(true).start()
            process.outputStream.close()
            val output = FutureTask { process.inputStream.bufferedReader().use { it.readText() } }
            Thread(output).apply { isDaemon = true; start() }
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "sandbox command timed out")
                return process.exitValue() to output.get(2, TimeUnit.SECONDS)
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        assertEquals(0, run("printf allowed > result").first)
        assertEquals("allowed", File(project, "result").readText())
        assertTrue(run("printf changed > '${outside.path}'").first != 0)
        assertTrue(run("printf changed > '${config.path}'").first != 0)
        assertEquals("readable", outside.readText())
        assertEquals("keep", config.readText())
        assertEquals(0 to "readable", run("cat '${outside.path}'"))
        val deniedDir = File(project, "private").apply { mkdirs() }
        val deniedFile = File(project, "credentials").apply { writeText("top-secret") }
        File(deniedDir, "secret").writeText("private-secret")
        val denyRunner = CommandSandbox(listOf(project.path), emptyList(), emptyList(), listOf(deniedDir.path, deniedFile.path))
        for (full in listOf(false, true)) {
            assertTrue(run("cat '${deniedFile.path}'", runner = denyRunner, full = full).first != 0)
            assertTrue(run("printf replaced > '${deniedFile.path}'", runner = denyRunner, full = full).first != 0)
            assertTrue(run("cat '${File(deniedDir, "secret").path}'", runner = denyRunner, full = full).first != 0)
            assertTrue(run("printf created > '${File(deniedDir, "new").path}'", runner = denyRunner, full = full).first != 0)
        }
        assertEquals(0, run("printf full > '${outside.path}'", runner = denyRunner, full = true).first)
        assertEquals("top-secret", deniedFile.readText())
        assertEquals("private-secret", File(deniedDir, "secret").readText())
        outside.writeText("readable")
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val responder = FutureTask {
                server.accept().use { client ->
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                }
            }
            Thread(responder).apply { isDaemon = true; start() }
            val command = "/usr/bin/curl -sS --max-time 2 http://127.0.0.1:${server.localPort}/"
            assertTrue(run(command).first != 0, "default command must not connect to host loopback")
            assertEquals(0 to "OK", run(command, network = true))
            responder.get(5, TimeUnit.SECONDS)
            assertTrue(run("printf changed > '${outside.path}'", network = true).first != 0)
        }
    }
}
