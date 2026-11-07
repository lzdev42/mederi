package xyz.mederi.workspace

import org.junit.Assume.assumeTrue
import xyz.mederi.tools.sandbox.LinuxCommandSandbox
import java.io.File
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceNativeLinuxTest {

    private fun fixture(block: (File) -> Unit) {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/worktree-evidence/native-linux-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "linux-").toFile().canonicalFile
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runLinuxSandbox(
        sandbox: LinuxCommandSandbox,
        directory: File,
        command: String,
        network: Boolean = false,
        fullAccess: Boolean = false
    ): Pair<Int, String> {
        val argv = sandbox.wrap(listOf("sh", "-c", command), networkAccess = network, fullAccess = fullAccess)
        val process = ProcessBuilder(argv)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = FutureTask { process.inputStream.bufferedReader().use { it.readText() } }
        Thread(output).apply { isDaemon = true; start() }
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "sandbox command timed out: $command")
            return process.exitValue() to output.get(2, TimeUnit.SECONDS)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun nativeVerifiedMountOrderingKeepsSiblingAndControlDataProtected() = fixture { root ->
        val required = System.getenv("MEDERI_REQUIRE_LINUX_SANDBOX") == "1"
        val linux = System.getProperty("os.name").lowercase().contains("linux")
        if (required) assertTrue(linux, "This verification requires a Linux host")

        val origProject = File(root, "orig-project").apply { mkdirs() }
        val origFile = File(origProject, "orig.txt").apply { writeText("orig-content") }

        val controlRoot = File(root, "control-root").apply { mkdirs() }
        val dataDb = File(controlRoot, "data.db").apply { writeText("sqlite-data") }
        val snapDir = File(controlRoot, "workspace-snapshots/snap1").apply { mkdirs() }
        val snapManifest = File(snapDir, "manifest.json").apply { writeText("{\"version\":1}") }

        val ws1Checkout = File(controlRoot, "worktrees/ws1/checkout").apply { mkdirs() }
        val ws1GitDir = File(ws1Checkout, ".git").apply { mkdirs() }
        val ws1GitConfig = File(ws1GitDir, "config").apply { writeText("git-config") }
        val ws1Src = File(ws1Checkout, "src").apply { mkdirs() }
        val ws1Main = File(ws1Src, "main.txt").apply { writeText("initial") }

        val ws2Checkout = File(controlRoot, "worktrees/ws2/checkout").apply { mkdirs() }
        val ws2Other = File(ws2Checkout, "other.txt").apply { writeText("sibling-content") }

        val artifactDir = File(origProject, ".mederi").apply { mkdirs() }
        val planDir = File(artifactDir, "plans").apply { mkdirs() }
        val artifactPlan = File(planDir, "p1.md").apply { writeText("initial-plan") }
        val artifactNotebook = File(artifactDir, "notebook.md").apply { writeText("initial-nb") }
        val artifactUnapproved = File(artifactDir, "unapproved.txt").apply { writeText("initial-unapproved") }

        val verifiedWs = VerifiedWorkspaceRoot.create(
            workspaceId = "ws_1",
            projectId = "proj_1",
            checkoutRoot = ws1Checkout.absolutePath,
            projectRelativePath = "",
            artifactDirectory = artifactDir.absolutePath
        )

        // 无论是否真实在 Linux 上执行，先断言由 wrap 生成的 bwrap 参数中精确的挂载顺序
        val probeSuccess = LinuxCommandSandbox.ProbeResult(0, "")
        val sandbox = LinuxCommandSandbox(
            projectDirs = listOf(origProject.absolutePath),
            extraPaths = emptyList(),
            tempDirs = emptyList(),
            protectedDirectories = listOf(controlRoot.absolutePath),
            deniedPaths = emptyList(),
            verifiedWorkspaceRoot = verifiedWs,
            probe = { probeSuccess }
        )

        val argv = sandbox.wrap(listOf("true"), networkAccess = false, fullAccess = false)
        val binds = argv.windowed(3).filter { it[0] == "--bind" || it[0] == "--ro-bind" }

        // 1. controlRoot ro-bind 必须在 verified ws checkout bind 之前
        val controlRoIndex = binds.indexOfFirst { it[0] == "--ro-bind" && it[1] == controlRoot.canonicalPath }
        val ws1RwIndex = binds.indexOfFirst { it[0] == "--bind" && it[1] == ws1Checkout.canonicalPath }
        assertTrue(controlRoIndex != -1, "controlRoot must be ro-bound")
        assertTrue(ws1RwIndex != -1, "ws1Checkout must be rw-bound")
        assertTrue(controlRoIndex < ws1RwIndex, "controlRoot ro-bind must precede ws1Checkout rw-bind")

        // 2. ws1/.git ro-bind 必须在 ws1Checkout bind 之后 (覆盖恢复只读)
        val gitRoIndex = binds.indexOfFirst { it[0] == "--ro-bind" && it[1] == ws1GitDir.canonicalPath }
        assertTrue(gitRoIndex != -1, "ws1/.git must be ro-bound")
        assertTrue(ws1RwIndex < gitRoIndex, "ws1Checkout rw-bind must precede .git ro-bind")

        // 3. 产物目录中未批准的文件不应该被 rw-bound
        assertFalse(binds.any { it[0] == "--bind" && it[1] == artifactUnapproved.canonicalPath })

        // 4. 若在 Linux 环境上且满足执行条件，执行真实 bwrap 断言
        assumeTrue("native bwrap mount ordering verification requires Linux", linux)
        if (required) assertTrue(sandbox.status("sh").available, sandbox.status("sh").detail)
        assumeTrue(sandbox.status("sh").detail, sandbox.status("sh").available)

        val (codeWriteWs, outWriteWs) = runLinuxSandbox(sandbox, ws1Checkout, "printf 'updated-main' > '${ws1Main.path}'")
        assertEquals(0, codeWriteWs, "Writing to verified workspace should succeed: $outWriteWs")
        assertEquals("updated-main", ws1Main.readText())

        val (codeWritePlan, outWritePlan) = runLinuxSandbox(sandbox, ws1Checkout, "printf 'updated-plan' > '${artifactPlan.path}'")
        assertEquals(0, codeWritePlan, "Writing to approved artifact plan should succeed: $outWritePlan")
        assertEquals("updated-plan", artifactPlan.readText())

        val (codeDataDb, _) = runLinuxSandbox(sandbox, ws1Checkout, "printf 'hacked' > '${dataDb.path}'")
        assertTrue(codeDataDb != 0, "Writing to control data.db should fail")
        assertEquals("sqlite-data", dataDb.readText())

        val (codeWs2, _) = runLinuxSandbox(sandbox, ws1Checkout, "printf 'hacked' > '${ws2Other.path}'")
        assertTrue(codeWs2 != 0, "Writing to sibling workspace should fail")
        assertEquals("sibling-content", ws2Other.readText())

        val (codeGit, _) = runLinuxSandbox(sandbox, ws1Checkout, "printf 'hacked' > '${ws1GitConfig.path}'")
        assertTrue(codeGit != 0, "Writing to .git/config in workspace should fail")
        assertEquals("git-config", ws1GitConfig.readText())
    }

    @Test
    fun nativeFullAccessRetainsBlacklistMasksWithoutHostMutation() = fixture { root ->
        val required = System.getenv("MEDERI_REQUIRE_LINUX_SANDBOX") == "1"
        val linux = System.getProperty("os.name").lowercase().contains("linux")
        if (required) assertTrue(linux, "This verification requires a Linux host")

        val origProject = File(root, "orig-proj").apply { mkdirs() }
        val origSecret = File(origProject, "secret.key").apply { writeText("orig-secret-key") }
        val externalSecret = File(root, "external-secret.key").apply { writeText("ext-secret-key") }

        val wsCheckout = File(root, "ws-checkout").apply { mkdirs() }
        val wsSecret = File(wsCheckout, "secret.key").apply { writeText("ws-mapped-secret") }
        val wsPublic = File(wsCheckout, "public.txt").apply { writeText("public-initial") }

        val artifactDir = File(origProject, ".mederi").apply { mkdirs() }

        val verifiedWs = VerifiedWorkspaceRoot.create(
            workspaceId = "ws_linux_full",
            projectId = "proj_linux_full",
            checkoutRoot = wsCheckout.absolutePath,
            projectRelativePath = "",
            artifactDirectory = artifactDir.absolutePath
        )

        val probeSuccess = LinuxCommandSandbox.ProbeResult(0, "")
        val sandbox = LinuxCommandSandbox(
            projectDirs = listOf(origProject.absolutePath),
            extraPaths = emptyList(),
            tempDirs = emptyList(),
            protectedDirectories = emptyList(),
            deniedPaths = listOf(origSecret.absolutePath, externalSecret.absolutePath),
            verifiedWorkspaceRoot = verifiedWs,
            probe = { probeSuccess }
        )

        // 无论平台，先断言全访问模式下 bwrap 参数依然包含黑名单遮罩 (tmpfs 000 或 ro-bind-data MASK_FD)
        val fullArgv = sandbox.wrap(listOf("true"), networkAccess = false, fullAccess = true)
        assertTrue(fullArgv.windowed(3).contains(listOf("--bind", "/", "/")), "full access should bind root rw")

        val maskedPaths = fullArgv.windowed(3).filter { it[0] == "--ro-bind-data" }.map { it[2] }
        assertTrue(wsSecret.canonicalPath in maskedPaths, "ws mapped secret must be masked even under full access")
        assertTrue(origSecret.canonicalPath in maskedPaths, "orig secret must be masked even under full access")
        assertTrue(externalSecret.canonicalPath in maskedPaths, "external secret must be masked even under full access")

        // 若在 Linux 环境上且满足执行条件，执行真实命令断言
        assumeTrue("native Linux full access blacklist verification requires Linux", linux)
        if (required) assertTrue(sandbox.status("sh").available, sandbox.status("sh").detail)
        assumeTrue(sandbox.status("sh").detail, sandbox.status("sh").available)

        val (codePublicWrite, outPublicWrite) = runLinuxSandbox(sandbox, wsCheckout, "printf 'public-updated' > '${wsPublic.path}'", fullAccess = true)
        assertEquals(0, codePublicWrite, "Writing to public file should succeed: $outPublicWrite")
        assertEquals("public-updated", wsPublic.readText())

        val (codeWsSecretRead, _) = runLinuxSandbox(sandbox, wsCheckout, "cat '${wsSecret.path}'", fullAccess = true)
        assertTrue(codeWsSecretRead != 0, "Reading workspace mapped secret must be denied under full access")

        val (codeWsSecretWrite, _) = runLinuxSandbox(sandbox, wsCheckout, "printf 'hacked' > '${wsSecret.path}'", fullAccess = true)
        assertTrue(codeWsSecretWrite != 0, "Writing to workspace mapped secret must be denied under full access")
        assertEquals("ws-mapped-secret", wsSecret.readText())

        val (codeOrigSecretRead, _) = runLinuxSandbox(sandbox, wsCheckout, "cat '${origSecret.path}'", fullAccess = true)
        assertTrue(codeOrigSecretRead != 0, "Reading orig secret must be denied under full access")

        val (codeExtRead, _) = runLinuxSandbox(sandbox, wsCheckout, "cat '${externalSecret.path}'", fullAccess = true)
        assertTrue(codeExtRead != 0, "Reading external secret must be denied under full access")
    }
}
