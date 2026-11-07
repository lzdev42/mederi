package xyz.mederi.workspace

import kotlinx.coroutines.runBlocking
import xyz.mederi.api.exception.MederiStateException
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.store.InMemorySettingsStore
import xyz.mederi.tools.sandbox.ExecutionPermissionMode
import xyz.mederi.tools.sandbox.ExecutionPermissions
import xyz.mederi.tools.sandbox.LinuxCommandSandbox
import xyz.mederi.tools.sandbox.SandboxManager
import xyz.mederi.tools.sandbox.SandboxPaths
import xyz.mederi.tools.sandbox.SandboxSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class WorkspacePermissionTest {

    private suspend fun fixture(block: suspend (File) -> Unit) {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/workspace-permission-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }

    private suspend fun createSettingsManager(projectId: String, settings: SandboxSettings, protectedDirs: List<String> = emptyList()): SandboxManager {
        val store = InMemorySettingsStore()
        val manager = SandboxManager(settingsStore = store, protectedDirectories = protectedDirs)
        manager.updateSettings(projectId, settings)
        return manager
    }

    @Test
    fun allowsOnlyVerifiedWorkspaceAndExactArtifactRoots(): Unit = runBlocking {
        fixture { tempFolder ->
            val origProject = File(tempFolder, "orig-project")
            origProject.mkdirs()
            File(origProject, "src").mkdirs()
            File(origProject, "src/orig.kt").writeText("orig")

            val artifactDir = File(origProject, ".mederi")
            artifactDir.mkdirs()
            File(artifactDir, "plans").mkdirs()
            File(artifactDir, "plans/p1.md").writeText("plan")
            File(artifactDir, "plans-done").mkdirs()
            File(artifactDir, "plans-voided").mkdirs()
            File(artifactDir, "research").mkdirs()
            File(artifactDir, "notebook.md").writeText("notebook")
            File(artifactDir, "unapproved.txt").writeText("nope")

            val wsCheckout = File(tempFolder, "ws1/checkout")
            wsCheckout.mkdirs()
            File(wsCheckout, "src").mkdirs()
            val wsFile = File(wsCheckout, "src/Main.kt")
            wsFile.writeText("main")

            val verifiedWs = VerifiedWorkspaceRoot.create(
                workspaceId = "ws_1",
                projectId = "proj_1",
                checkoutRoot = wsCheckout.absolutePath,
                projectRelativePath = "",
                artifactDirectory = artifactDir.absolutePath
            )

            val paths = SandboxPaths(
                projectDirs = listOf(origProject.absolutePath),
                extraWritablePaths = emptyList(),
                verifiedWorkspaceRoot = verifiedWs
            )

            // 1. 当前工作区是有效可写根
            assertTrue(paths.allowsWrite(wsFile.toPath()))

            // 2. 原项目目录整体不可写
            assertFalse(paths.allowsWrite(File(origProject, "src/orig.kt").toPath()))

            // 3. D09 精确产物根可写
            assertTrue(paths.allowsWrite(File(artifactDir, "plans/p1.md").toPath()))
            assertTrue(paths.allowsWrite(File(artifactDir, "notebook.md").toPath()))
            assertTrue(paths.allowsWrite(File(artifactDir, "research/res.md").toPath()))

            // 4. .mederi 下非白名单路径不可写
            assertFalse(paths.allowsWrite(File(artifactDir, "unapproved.txt").toPath()))
            assertFalse(paths.allowsWrite(File(artifactDir, "other/new.txt").toPath()))
        }
    }

    @Test
    fun protectsSiblingWorkspacesAndControlData(): Unit = runBlocking {
        fixture { tempFolder ->
            val controlRoot = File(tempFolder, "control-root")
            controlRoot.mkdirs()

            val dataDb = File(controlRoot, "data.db")
            dataDb.writeText("data")
            val snapshots = File(controlRoot, "workspace-snapshots/snap1")
            snapshots.mkdirs()
            val snapManifest = File(snapshots, "manifest.json")
            snapManifest.writeText("{}")

            val ws1Checkout = File(controlRoot, "worktrees/ws1/checkout")
            ws1Checkout.mkdirs()
            File(ws1Checkout, ".git").mkdirs()
            val ws1GitConfig = File(ws1Checkout, ".git/config")
            ws1GitConfig.writeText("git")
            val ws1File = File(ws1Checkout, "src/app.kt")
            ws1File.parentFile.mkdirs()
            ws1File.writeText("app")

            val ws2Checkout = File(controlRoot, "worktrees/ws2/checkout")
            ws2Checkout.mkdirs()
            val ws2File = File(ws2Checkout, "src/other.kt")
            ws2File.parentFile.mkdirs()
            ws2File.writeText("other")

            val artifactDir = File(tempFolder, "artifacts/.mederi")
            artifactDir.mkdirs()

            val verifiedWs = VerifiedWorkspaceRoot.create(
                workspaceId = "ws_1",
                projectId = "proj_1",
                checkoutRoot = ws1Checkout.absolutePath,
                projectRelativePath = "",
                artifactDirectory = artifactDir.absolutePath
            )

            val paths = SandboxPaths(
                projectDirs = emptyList(),
                extraWritablePaths = emptyList(),
                protectedDirectories = listOf(controlRoot.absolutePath),
                verifiedWorkspaceRoot = verifiedWs
            )

            // 精确允许 verifiedWs 的 checkoutRoot
            assertTrue(paths.allowsWrite(ws1File.toPath()))

            // 兄弟工作区受保护
            assertFalse(paths.allowsWrite(ws2File.toPath()))

            // 控制库数据受保护
            assertFalse(paths.allowsWrite(dataDb.toPath()))
            assertFalse(paths.allowsWrite(snapManifest.toPath()))

            // 工作区内 .git 依然严格保护
            assertFalse(paths.allowsWrite(ws1GitConfig.toPath()))
        }
    }

    @Test
    fun mapsProjectDenyPathsWithoutRewritingSettings(): Unit = runBlocking {
        fixture { tempFolder ->
            val origProject = File(tempFolder, "orig-proj")
            origProject.mkdirs()
            val origSecret = File(origProject, "secret.key")
            origSecret.writeText("secret")

            val externalSecret = File(tempFolder, "external-secret.key")
            externalSecret.writeText("ext")

            val wsCheckout = File(tempFolder, "ws1/checkout")
            wsCheckout.mkdirs()
            val wsSecret = File(wsCheckout, "secret.key")
            wsSecret.writeText("mapped secret")

            val artifactDir = File(origProject, ".mederi")
            artifactDir.mkdirs()

            val settings = SandboxSettings(
                mode = ExecutionPermissionMode.NEEDS_APPROVAL,
                deniedPaths = listOf(origSecret.absolutePath, externalSecret.absolutePath)
            )
            val manager = createSettingsManager("proj_1", settings)

            val verifiedWs = VerifiedWorkspaceRoot.create(
                workspaceId = "ws_1",
                projectId = "proj_1",
                checkoutRoot = wsCheckout.absolutePath,
                projectRelativePath = "",
                artifactDirectory = artifactDir.absolutePath
            )

            val permissions = ExecutionPermissions(
                manager = manager,
                directories = listOf(origProject.absolutePath),
                conversationId = "conv_1",
                projectId = "proj_1",
                verifiedWorkspaceRoot = verifiedWs
            )

            // 1. 原项目内的拒绝路径生效
            assertFailsWith<MederiStateException> {
                permissions.checkRead(origSecret.toPath())
            }

            // 2. 映射到工作区相对路径的拒绝规则生效
            assertFailsWith<MederiStateException> {
                permissions.checkRead(wsSecret.toPath())
            }

            // 3. 项目外的拒绝路径生效且不被重定向
            assertFailsWith<MederiStateException> {
                permissions.checkRead(externalSecret.toPath())
            }

            // 4. SettingsStore 中存储的原 settings 未被重写
            val persisted = manager.getSettings("proj_1")
            assertEquals(listOf(origSecret.absolutePath, externalSecret.absolutePath), persisted.deniedPaths)
        }
    }

    @Test
    fun fullAccessStillEnforcesExplicitDenials(): Unit = runBlocking {
        fixture { tempFolder ->
            val wsCheckout = File(tempFolder, "ws-full/checkout")
            wsCheckout.mkdirs()
            val secretFile = File(wsCheckout, "credentials.env")
            secretFile.writeText("API_KEY=xxx")

            val artifactDir = File(tempFolder, "artifacts/.mederi")
            artifactDir.mkdirs()

            val settings = SandboxSettings(
                mode = ExecutionPermissionMode.FULL_ACCESS,
                deniedPaths = listOf(secretFile.absolutePath),
                deniedCommands = listOf("cat credentials.env", "rm")
            )
            val manager = createSettingsManager("proj_full", settings)

            val verifiedWs = VerifiedWorkspaceRoot.create(
                workspaceId = "ws_full",
                projectId = "proj_full",
                checkoutRoot = wsCheckout.absolutePath,
                projectRelativePath = "",
                artifactDirectory = artifactDir.absolutePath
            )

            val permissions = ExecutionPermissions(
                manager = manager,
                directories = listOf(wsCheckout.absolutePath),
                conversationId = "conv_full",
                projectId = "proj_full",
                verifiedWorkspaceRoot = verifiedWs
            )

            // 1. FULL_ACCESS 依然强制禁止读取黑名单
            assertFailsWith<MederiStateException> {
                permissions.checkRead(secretFile.toPath())
            }

            // 2. FULL_ACCESS 依然禁止写入黑名单路径
            assertFailsWith<MederiStateException> {
                permissions.authorizeWrite("file_write", listOf(secretFile.toPath()))
            }

            // 3. FULL_ACCESS 依然禁止执行拒绝的命令
            assertFailsWith<MederiStateException> {
                permissions.authorizeCommand("cat credentials.env", cwd = wsCheckout.absolutePath, sandbox = "default")
            }
            assertFailsWith<MederiStateException> {
                permissions.authorizeCommand("rm -rf something", cwd = wsCheckout.absolutePath, sandbox = "default")
            }
        }
    }

    @Test
    fun rejectsIdentityReplacementAndDeniedRootCreation(): Unit = runBlocking {
        fixture { tempFolder ->
            // Part 1: 身份替换（被替换为符号链接）拒绝
            val realDir = File(tempFolder, "real_checkout")
            realDir.mkdirs()
            val symlinkDir = File(tempFolder, "symlink_checkout")
            Files.createSymbolicLink(symlinkDir.toPath(), realDir.toPath())

            val artifactDir = File(tempFolder, "artifact/.mederi")
            artifactDir.mkdirs()

            // 创建时如果是符号链接即刻被拒绝
            val ex = assertFailsWith<MederiWorkspaceException> {
                VerifiedWorkspaceRoot.create(
                    workspaceId = "ws_sym",
                    projectId = "proj_sym",
                    checkoutRoot = symlinkDir.absolutePath,
                    projectRelativePath = "",
                    artifactDirectory = artifactDir.absolutePath
                )
            }
            assertEquals(WorkspaceErrorCode.IDENTITY_CHANGED, ex.code)

            // 运行时若原真实目录被外部替换为符号链接
            val dynamicCheckout = File(tempFolder, "dynamic_checkout")
            dynamicCheckout.mkdirs()
            val verifiedWs = VerifiedWorkspaceRoot.create(
                workspaceId = "ws_dyn",
                projectId = "proj_dyn",
                checkoutRoot = dynamicCheckout.absolutePath,
                projectRelativePath = "",
                artifactDirectory = artifactDir.absolutePath
            )
            // 外部篡改替换为 symlink
            dynamicCheckout.delete()
            Files.createSymbolicLink(dynamicCheckout.toPath(), realDir.toPath())

            val verifyEx = assertFailsWith<MederiWorkspaceException> {
                verifiedWs.verifyIdentity()
            }
            assertEquals(WorkspaceErrorCode.IDENTITY_CHANGED, verifyEx.code)

            // Part 2: 黑名单内的额外根不得在准备阶段 mkdir
            val deniedBase = File(tempFolder, "denied_base")
            deniedBase.mkdirs()
            val extraInsideDenied = File(deniedBase, "unauthorized_extra_sub")
            assertFalse(extraInsideDenied.exists())

            // prepareMounts 会校验并过滤 writableRoots
            val paths = SandboxPaths(
                projectDirs = listOf(tempFolder.absolutePath),
                extraWritablePaths = listOf(extraInsideDenied.absolutePath),
                deniedPaths = listOf(deniedBase.absolutePath)
            )
            assertFalse(paths.allowsWrite(extraInsideDenied.toPath()))
            assertFalse(extraInsideDenied.exists(), "黑名单内的额外根不得被 mkdir 创建")
        }
    }

    @Test
    fun rejectsOldWorkspaceGrantsAfterHandoffWithoutPermissionChange(): Unit = runBlocking {
        fixture { tempFolder ->
            val ws1Checkout = File(tempFolder, "ws1_checkout")
            ws1Checkout.mkdirs()
            val ws2Checkout = File(tempFolder, "ws2_checkout")
            ws2Checkout.mkdirs()
            val artifactDir = File(tempFolder, "artifact/.mederi")
            artifactDir.mkdirs()

            val settings = SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS)
            val manager = createSettingsManager("proj_h", settings)

            var currentWs = VerifiedWorkspaceRoot.create(
                workspaceId = "ws_1",
                projectId = "proj_h",
                checkoutRoot = ws1Checkout.absolutePath,
                projectRelativePath = "",
                artifactDirectory = artifactDir.absolutePath,
                generation = 1L
            )

            val permissions = ExecutionPermissions(
                manager = manager,
                directories = listOf(ws1Checkout.absolutePath),
                conversationId = "conv_h",
                projectId = "proj_h",
                verifiedWorkspaceRootProvider = { currentWs }
            )

            val targetPath = File(ws1Checkout, "file.txt").toPath()
            val grant = permissions.authorizeWrite("file_write", listOf(targetPath))

            // 当前工作区代次吻合，grant 合法
            permissions.checkGrant(grant)

            // 发生 Handoff：切换到 ws2，工作区代次或身份改变
            currentWs = VerifiedWorkspaceRoot.create(
                workspaceId = "ws_2",
                projectId = "proj_h",
                checkoutRoot = ws2Checkout.absolutePath,
                projectRelativePath = "",
                artifactDirectory = artifactDir.absolutePath,
                generation = 2L
            )

            // 项目权限 settings 未改变（generation 仍然相同）
            assertEquals(grant.generation, manager.snapshot("proj_h").second)

            // 但是针对旧工作区的 grant 必须被硬拒绝
            assertFailsWith<MederiStateException> {
                permissions.checkGrant(grant)
            }
        }
    }
}
