package xyz.mederi.workspace

import org.junit.Assume.assumeTrue
import xyz.mederi.tools.sandbox.CommandSandbox
import java.io.File
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceNativeMacTest {

    private fun fixture(block: (File) -> Unit) {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/worktree-evidence/native-mac-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "mac-").toFile().canonicalFile
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runMacSandbox(
        sandbox: CommandSandbox,
        directory: File,
        command: String,
        network: Boolean = false,
        fullAccess: Boolean = false
    ): Pair<Int, String> {
        val wrapped = sandbox.wrap(command, networkAccess = network, unsandboxed = fullAccess)
        val process = ProcessBuilder(wrapped.argv)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = FutureTask { process.inputStream.bufferedReader().use { it.readText() } }
        Thread(output).apply { isDaemon = true; start() }
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "sandbox command timed out: $command")
            return process.exitValue() to output.get(2, TimeUnit.SECONDS)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun nativeVerifiedWorkspaceAndArtifactWritesKeepControlDataProtected() = fixture { root ->
        val required = System.getenv("MEDERI_REQUIRE_MACOS_SANDBOX") == "1"
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        if (required) assertTrue(mac, "This verification requires a macOS host")
        assumeTrue("native Seatbelt verification requires macOS", mac)

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

        val sandbox = CommandSandbox(
            projectDirs = listOf(origProject.absolutePath),
            extraWritablePaths = emptyList(),
            protectedDirectories = listOf(controlRoot.absolutePath),
            deniedPaths = emptyList(),
            verifiedWorkspaceRoot = verifiedWs
        )

        if (required) assertTrue(sandbox.status().available, sandbox.status().detail)
        assumeTrue(sandbox.status().detail, sandbox.status().available)

        println("[LOG] seatbelt profile (needs approval):")
        println(sandbox.seatbeltProfile(fullAccess = false))

        // 1. 验证当前 verified workspace 内部可写
        val (codeWriteWs, outWriteWs) = runMacSandbox(sandbox, ws1Checkout, "printf 'updated-main' > '${ws1Main.path}'")
        assertEquals(0, codeWriteWs, "Writing to verified workspace should succeed: $outWriteWs")
        assertEquals("updated-main", ws1Main.readText())

        // 2. 验证精确产物根可写
        val (codeWritePlan, outWritePlan) = runMacSandbox(sandbox, ws1Checkout, "printf 'updated-plan' > '${artifactPlan.path}'")
        assertEquals(0, codeWritePlan, "Writing to approved artifact plan should succeed: $outWritePlan")
        assertEquals("updated-plan", artifactPlan.readText())

        val (codeWriteNb, outWriteNb) = runMacSandbox(sandbox, ws1Checkout, "printf 'updated-nb' > '${artifactNotebook.path}'")
        assertEquals(0, codeWriteNb, "Writing to approved notebook should succeed: $outWriteNb")
        assertEquals("updated-nb", artifactNotebook.readText())

        // 3. 验证产物根未批准路径不可写
        val (codeWriteUnapproved, _) = runMacSandbox(sandbox, ws1Checkout, "printf 'hacked' > '${artifactUnapproved.path}'")
        assertTrue(codeWriteUnapproved != 0, "Writing to unapproved artifact path should fail")
        assertEquals("initial-unapproved", artifactUnapproved.readText())

        // 4. 验证控制数据保护 (data.db, snapshot manifest)
        val (codeDataDb, _) = runMacSandbox(sandbox, ws1Checkout, "printf 'hacked' > '${dataDb.path}'")
        assertTrue(codeDataDb != 0, "Writing to control data.db should fail")
        assertEquals("sqlite-data", dataDb.readText())

        val (codeSnap, _) = runMacSandbox(sandbox, ws1Checkout, "printf 'hacked' > '${snapManifest.path}'")
        assertTrue(codeSnap != 0, "Writing to snapshot manifest should fail")
        assertEquals("{\"version\":1}", snapManifest.readText())

        // 5. 验证兄弟工作区受保护不可写
        val (codeWs2, _) = runMacSandbox(sandbox, ws1Checkout, "printf 'hacked' > '${ws2Other.path}'")
        assertTrue(codeWs2 != 0, "Writing to sibling workspace should fail")
        assertEquals("sibling-content", ws2Other.readText())

        // 6. 验证工作区内 .git 元数据受保护不可写
        val (codeGit, _) = runMacSandbox(sandbox, ws1Checkout, "printf 'hacked' > '${ws1GitConfig.path}'")
        assertTrue(codeGit != 0, "Writing to .git/config in workspace should fail")
        assertEquals("git-config", ws1GitConfig.readText())

        // 7. 验证原项目不可写
        val (codeOrig, _) = runMacSandbox(sandbox, ws1Checkout, "printf 'hacked' > '${origFile.path}'")
        assertTrue(codeOrig != 0, "Writing to original project should fail")
        assertEquals("orig-content", origFile.readText())
    }

    @Test
    fun nativeFullAccessRetainsProjectBlacklistReadWriteDenial() = fixture { root ->
        val required = System.getenv("MEDERI_REQUIRE_MACOS_SANDBOX") == "1"
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        if (required) assertTrue(mac, "This verification requires a macOS host")
        assumeTrue("native Seatbelt verification requires macOS", mac)

        val origProject = File(root, "orig-proj").apply { mkdirs() }
        val origSecret = File(origProject, "secret.key").apply { writeText("orig-secret-key") }

        val externalSecret = File(root, "external-secret.key").apply { writeText("ext-secret-key") }
        val outsideFile = File(root, "outside.txt").apply { writeText("outside-initial") }

        val wsCheckout = File(root, "ws-checkout").apply { mkdirs() }
        val wsSecret = File(wsCheckout, "secret.key").apply { writeText("ws-mapped-secret") }
        val wsPublic = File(wsCheckout, "public.txt").apply { writeText("public-initial") }

        val artifactDir = File(origProject, ".mederi").apply { mkdirs() }

        val verifiedWs = VerifiedWorkspaceRoot.create(
            workspaceId = "ws_full",
            projectId = "proj_full",
            checkoutRoot = wsCheckout.absolutePath,
            projectRelativePath = "",
            artifactDirectory = artifactDir.absolutePath
        )

        val sandbox = CommandSandbox(
            projectDirs = listOf(origProject.absolutePath),
            extraWritablePaths = emptyList(),
            protectedDirectories = emptyList(),
            deniedPaths = listOf(origSecret.absolutePath, externalSecret.absolutePath),
            verifiedWorkspaceRoot = verifiedWs
        )

        if (required) assertTrue(sandbox.status().available, sandbox.status().detail)
        assumeTrue(sandbox.status().detail, sandbox.status().available)

        println("[LOG] seatbelt profile (full access):")
        val fullProfile = sandbox.seatbeltProfile(fullAccess = true)
        println(fullProfile)

        // 1. 在 full access 模式下，普通文件可读写
        val (codePublicWrite, outPublicWrite) = runMacSandbox(sandbox, wsCheckout, "printf 'public-updated' > '${wsPublic.path}'", fullAccess = true)
        assertEquals(0, codePublicWrite, "Writing to public file under full access should succeed: $outPublicWrite")
        assertEquals("public-updated", wsPublic.readText())

        val (codeOutsideWrite, outOutsideWrite) = runMacSandbox(sandbox, wsCheckout, "printf 'outside-updated' > '${outsideFile.path}'", fullAccess = true)
        assertEquals(0, codeOutsideWrite, "Writing outside project under full access should succeed: $outOutsideWrite")
        assertEquals("outside-updated", outsideFile.readText())

        // 2. 映射到工作区相对路径的黑名单文件，即使 full access 也是读写均被拒绝
        val (codeWsSecretRead, outWsSecretRead) = runMacSandbox(sandbox, wsCheckout, "cat '${wsSecret.path}'", fullAccess = true)
        println("[LOG] wsSecret read: exit=$codeWsSecretRead, out=$outWsSecretRead")
        assertTrue(codeWsSecretRead != 0, "Reading workspace mapped secret must be denied even under full access")

        val (codeWsSecretWrite, outWsSecretWrite) = runMacSandbox(sandbox, wsCheckout, "printf 'hacked' > '${wsSecret.path}'", fullAccess = true)
        println("[LOG] wsSecret write: exit=$codeWsSecretWrite, out=$outWsSecretWrite")
        assertTrue(codeWsSecretWrite != 0, "Writing to workspace mapped secret must be denied even under full access")
        assertEquals("ws-mapped-secret", wsSecret.readText())

        // 3. 原项目内的黑名单文件在 full access 下也是读写均被拒绝
        val (codeOrigSecretRead, outOrigSecretRead) = runMacSandbox(sandbox, wsCheckout, "cat '${origSecret.path}'", fullAccess = true)
        println("[LOG] origSecret read: exit=$codeOrigSecretRead, out=$outOrigSecretRead")
        assertTrue(codeOrigSecretRead != 0, "Reading orig secret must be denied under full access")

        val (codeOrigSecretWrite, outOrigSecretWrite) = runMacSandbox(sandbox, wsCheckout, "printf 'hacked' > '${origSecret.path}'", fullAccess = true)
        println("[LOG] origSecret write: exit=$codeOrigSecretWrite, out=$outOrigSecretWrite")
        assertTrue(codeOrigSecretWrite != 0, "Writing to orig secret must be denied under full access")
        assertEquals("orig-secret-key", origSecret.readText())

        // 4. 外部显式黑名单文件在 full access 下读写均被拒绝
        val (codeExtRead, outExtRead) = runMacSandbox(sandbox, wsCheckout, "cat '${externalSecret.path}'", fullAccess = true)
        println("[LOG] externalSecret read: exit=$codeExtRead, out=$outExtRead")
        assertTrue(codeExtRead != 0, "Reading external secret must be denied under full access")

        val (codeExtWrite, outExtWrite) = runMacSandbox(sandbox, wsCheckout, "printf 'hacked' > '${externalSecret.path}'", fullAccess = true)
        println("[LOG] externalSecret write: exit=$codeExtWrite, out=$outExtWrite")
        assertTrue(codeExtWrite != 0, "Writing to external secret must be denied under full access")
        assertEquals("ext-secret-key", externalSecret.readText())
    }
}
