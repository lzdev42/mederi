package xyz.mederi.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.store.InMemorySettingsStore
import xyz.mederi.store.InMemoryWorkspaceStore
import xyz.mederi.tools.sandbox.ExecutionPermissionMode
import xyz.mederi.tools.sandbox.ExecutionPermissions
import xyz.mederi.tools.sandbox.SandboxManager
import xyz.mederi.tools.sandbox.SandboxSettings
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class WorkspaceEnvironmentTest {

    private suspend fun fixture(block: suspend (File) -> Unit) {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/workspace-environment-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }

    /**
     * T08: 为实际执行脚本的测试装配真实 FULL_ACCESS 权限——
     * setup/action 不得在 permissions=null 下裸跑，生产路径已强制要求真实权限。
     */
    private suspend fun fullAccessPermissions(tempFolder: File): ExecutionPermissions {
        val settings = SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS)
        val settingsStore = InMemorySettingsStore()
        val manager = SandboxManager(settingsStore = settingsStore)
        manager.updateSettings("proj_test", settings)
        return ExecutionPermissions(
            manager = manager,
            directories = listOf(tempFolder.absolutePath),
            conversationId = "conv_full",
            projectId = "proj_test",
            verifiedWorkspaceRoot = null
        )
    }

    private fun sampleWorkspace(root: File, id: String = "ws_test"): Workspace {
        return Workspace(
            id = id,
            projectId = "proj_test",
            kind = WorkspaceKind.MANAGED_WORKTREE,
            checkoutRoot = root.absolutePath,
            projectRelativePath = "",
            state = WorkspaceState.PREPARING,
            generation = 1L,
            createdAt = Instant.now().toString(),
            lastUsedAt = Instant.now().toString()
        )
    }

    @Test
    fun selectsPlatformOverrideIncludingExplicitEmptyScript() {
        val store = InMemoryWorkspaceStore()
        val service = WorkspaceEnvironmentService(store)

        val script = PlatformScript(
            default = "echo default",
            macos = "",             // 显式空字符串：Mac 平台显式不执行
            linux = "echo linux",   // Linux 覆盖
            windows = null          // Windows 为 null：回退到 default
        )

        // 1. macOS: 显式空字符串，不回退 default
        assertEquals("", service.resolvePlatformScript(script, "Mac OS X"))

        // 2. Linux: 覆盖生效
        assertEquals("echo linux", service.resolvePlatformScript(script, "Linux"))

        // 3. Windows: null 回退到 default
        assertEquals("echo default", service.resolvePlatformScript(script, "Windows 11"))
    }

    @Test
    fun waitsForExecutionApprovalWithoutStartingModel(): Unit = runBlocking {
        fixture { tempFolder ->
            val store = InMemoryWorkspaceStore()
            val service = WorkspaceEnvironmentService(store)
            val ws = sampleWorkspace(tempFolder)
            store.saveWorkspace(ws)

            val env = ProjectEnvironment(
                id = "env_approval",
                name = "Env with network sandbox",
                version = 1L,
                setup = PlatformScript(default = "echo installing", sandbox = "network")
            )
            val hash = service.computeEnvironmentHash(env)

            val settings = SandboxSettings(mode = ExecutionPermissionMode.NEEDS_APPROVAL)
            val settingsStore = InMemorySettingsStore()
            val manager = SandboxManager(settingsStore = settingsStore)
            manager.updateSettings("proj_test", settings)

            val permissions = ExecutionPermissions(
                manager = manager,
                directories = listOf(tempFolder.absolutePath),
                conversationId = "conv_1",
                projectId = "proj_test",
                verifiedWorkspaceRoot = null
            )

            // dryRun 模拟等待审批分支
            val op = service.executeSetup(
                workspace = ws,
                environment = env,
                expectedHash = hash,
                requestId = "req_1",
                permissions = permissions,
                dryRun = true
            )

            assertEquals(WorkspaceOperationState.WAITING_APPROVAL, op.state)
            assertEquals("conv_1", op.sessionId)
            // 工作区仍处于 PREPARING 状态，模型 gate 关闭
            val persistedWs = store.getWorkspace(ws.id)
            assertNotNull(persistedWs)
            assertNotEquals(WorkspaceState.READY, persistedWs.state)
        }
    }

    @Test
    fun invalidatesApprovalWhenConfigurationHashChanges(): Unit = runBlocking {
        fixture { tempFolder ->
            val store = InMemoryWorkspaceStore()
            val service = WorkspaceEnvironmentService(store)
            val ws = sampleWorkspace(tempFolder)
            store.saveWorkspace(ws)

            val env = ProjectEnvironment(
                id = "env_hash",
                name = "Env Hash Test",
                version = 1L,
                setup = PlatformScript(default = "echo setup")
            )
            val oldHash = "stale_hash_value"

            // 期待 oldHash，但环境指纹已经不同 -> 抛出 ENVIRONMENT_CHANGED
            val ex = assertFailsWith<MederiWorkspaceException> {
                service.executeSetup(
                    workspace = ws,
                    environment = env,
                    expectedHash = oldHash,
                    requestId = "req_hash_mismatch"
                )
            }
            assertEquals(WorkspaceErrorCode.ENVIRONMENT_CHANGED, ex.code)
        }
    }

    @Test
    fun deduplicatesActionRequestsAndSetupRetries(): Unit = runBlocking {
        fixture { tempFolder ->
            val store = InMemoryWorkspaceStore()
            val service = WorkspaceEnvironmentService(store)
            val ws = sampleWorkspace(tempFolder)
            store.saveWorkspace(ws)

            val action = EnvironmentAction(
                id = "act_build",
                name = "Build",
                script = PlatformScript(default = "echo build_done")
            )
            val env = ProjectEnvironment(
                id = "env_act",
                name = "Action Test",
                version = 1L,
                actions = listOf(action)
            )

            val actionInput = WorkspaceActionInput(
                environmentId = "env_act",
                actionId = "act_build",
                environmentVersion = 1L,
                requestId = "dedup_req_1"
            )

            val permissions = fullAccessPermissions(tempFolder)

            // 第一次执行 Action
            val op1 = service.executeAction(ws, actionInput, env, permissions = permissions)
            assertEquals(WorkspaceOperationState.SUCCEEDED, op1.state)

            // 相同 requestId + 相同 requestHash：幂等返回原操作
            val op2 = service.executeAction(ws, actionInput, env, permissions = permissions)
            assertEquals(op1.id, op2.id)

            // 相同 requestId + 不同环境配置 (不同 requestHash)：拒绝并抛出 INVALID_REQUEST
            val modifiedEnv = env.copy(name = "Modified Action Env")
            val ex = assertFailsWith<MederiWorkspaceException> {
                service.executeAction(ws, actionInput, modifiedEnv, permissions = permissions)
            }
            assertEquals(WorkspaceErrorCode.INVALID_REQUEST, ex.code)
        }
    }

    @Test
    fun keepsFailedOrUnknownSetupNonExecutable(): Unit = runBlocking {
        fixture { tempFolder ->
            val store = InMemoryWorkspaceStore()
            val service = WorkspaceEnvironmentService(store)
            val ws = sampleWorkspace(tempFolder)
            store.saveWorkspace(ws)

            val env = ProjectEnvironment(
                id = "env_fail",
                name = "Failing Setup",
                version = 1L,
                setup = PlatformScript(default = "exit 1")
            )
            val hash = service.computeEnvironmentHash(env)

            // T08: 真实权限下失败通过操作状态反馈（不抛异常）；权限缺失时才会抛 INVALID_REQUEST。
            val returnedOp = service.executeSetup(ws, env, hash, "req_fail", permissions = fullAccessPermissions(tempFolder))
            assertEquals(WorkspaceOperationState.FAILED, returnedOp.state)

            val failedOp = store.listOperations(ws.projectId).find { it.kind == WorkspaceOperationKind.SETUP }
            assertNotNull(failedOp)
            assertEquals(WorkspaceOperationState.FAILED, failedOp.state)

            // 工作区仍为非 READY 状态
            val persistedWs = store.getWorkspace(ws.id)
            assertNotNull(persistedWs)
            assertNotEquals(WorkspaceState.READY, persistedWs.state)
        }
    }

    @Test
    fun cancelsRegisteredProcessGroupAndPaginatesUtf8Logs(): Unit = runBlocking {
        fixture { tempFolder ->
            val store = InMemoryWorkspaceStore()
            val service = WorkspaceEnvironmentService(store)
            val ws = sampleWorkspace(tempFolder)
            store.saveWorkspace(ws)

            val opId = "op_test_log"
            val logBuf = service.getLogBuffer(opId)

            // 写入多字节 UTF-8 文本（中文与 Emoji）
            val fullMessage = "Mederi 平台工作区操作日志输出：\n阶段 1：初始化环境。\n阶段 2：构建中 🚀 ... 完成。\n"
            logBuf.append(fullMessage)

            // 测试分块按 UTF-8 边界安全读取（每次小字节读取，验证不出现乱码）
            val collected = StringBuilder()
            var offset = 0L
            while (true) {
                val chunk = service.readLog(opId, afterOffset = offset, maxBytes = 7)
                collected.append(chunk.text)
                offset = chunk.nextOffset
                if (chunk.eof) break
            }
            assertEquals(fullMessage, collected.toString(), "按小字节分块分页读取不能破坏多字节 UTF-8 编码")

            // 测试取消操作与状态变更
            val op = WorkspaceOperation(
                id = opId,
                projectId = ws.projectId,
                workspaceId = ws.id,
                kind = WorkspaceOperationKind.ACTION,
                state = WorkspaceOperationState.RUNNING,
                phase = WorkspacePhase.SETUP,
                idempotencyKey = "ACTION:cancel_test",
                requestHash = "hash",
                operationPlan = WorkspaceOperationPlan.ActionPlan("hash", workspaceId = ws.id, environmentId = "e", actionId = "a", environmentVersion = 1L),
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString()
            )
            store.saveOperation(op)

            service.cancelOperation(opId)

            val cancelledOp = store.getOperation(opId)
            assertNotNull(cancelledOp)
            assertEquals(WorkspaceOperationState.CANCELLED, cancelledOp.state)
            assertTrue(cancelledOp.cancelRequested)
        }
    }

    @Test
    fun stoppingEnvironmentActionClearsOnlyItsOwnPendingApproval(): Unit = runBlocking {
        fixture { root ->
            val store = InMemoryWorkspaceStore()
            val service = WorkspaceEnvironmentService(store)
            val workspace = sampleWorkspace(root)
            val owner = "conv_action_owner"
            val manager = SandboxManager(settingsStore = InMemorySettingsStore())
            val permissions = ExecutionPermissions(
                manager = manager,
                directories = listOf(root.path),
                conversationId = owner,
                projectId = workspace.projectId,
                verifiedWorkspaceRoot = null
            )
            val firstScript = "printf first > first.txt"
            val secondScript = "printf second > second.txt"
            val environment = ProjectEnvironment(
                id = "env_cancel",
                name = "Action cancellation",
                version = 1L,
                actions = listOf(
                    EnvironmentAction("first", "First", script = PlatformScript(default = firstScript, sandbox = "off")),
                    EnvironmentAction("second", "Second", script = PlatformScript(default = secondScript, sandbox = "off"))
                )
            )
            try {
                coroutineScope {
                    val first = async {
                        service.executeAction(
                            workspace,
                            WorkspaceActionInput(environment.id, "first", environment.version, "first", owner),
                            environment,
                            permissions
                        )
                    }
                    val second = async {
                        service.executeAction(
                            workspace,
                            WorkspaceActionInput(environment.id, "second", environment.version, "second", owner),
                            environment,
                            permissions
                        )
                    }
                    try {
                        withTimeout(5_000) {
                            while (manager.pendingApprovals(owner).size < 2) delay(10)
                        }
                        val op = store.listOperations(workspace.projectId).single { it.idempotencyKey == "ACTION:first" }
                        assertEquals(owner, op.sessionId)
                        assertTrue(withTimeout(5_000) { service.stopOperationExecution(op.id) })
                        assertFailsWith<CancellationException> { first.await() }
                        assertEquals(WorkspaceOperationState.CANCELLED, store.getOperation(op.id)?.state)
                        assertEquals(listOf(secondScript), manager.pendingApprovals(owner).map { it.command })
                        assertFalse(File(root, "first.txt").exists())
                        assertFalse(File(root, "second.txt").exists())
                    } finally {
                        first.cancelAndJoin()
                        second.cancelAndJoin()
                    }
                    assertTrue(manager.pendingApprovals(owner).isEmpty())
                }
            } finally {
                manager.close()
            }
        }
    }

    @Test
    fun supportsVersionedAndExplicitlySelectedIgnoredEnvironmentConfiguration(): Unit = runBlocking {
        fixture { tempFolder ->
            val store = InMemoryWorkspaceStore()
            val service = WorkspaceEnvironmentService(store)

            val env1 = ProjectEnvironment(
                id = "node20",
                name = "Node 20 LTS",
                version = 0L,
                setup = PlatformScript(default = "npm install")
            )

            // 1. 首次保存环境（期望版本 0L）
            val savedV1 = service.saveEnvironment(tempFolder, env1, expectedVersion = 0L)
            assertEquals(1L, savedV1.version)

            val loadedV1 = service.getEnvironment(tempFolder, "node20")
            assertNotNull(loadedV1)
            assertEquals(1L, loadedV1.version)
            assertEquals("Node 20 LTS", loadedV1.name)

            // 2. CAS 冲突校验：期望版本 2L 但当前实际是 1L
            assertFailsWith<MederiWorkspaceException> {
                service.saveEnvironment(tempFolder, savedV1, expectedVersion = 2L)
            }

            // 3. 正确 CAS 版本递增保存
            val savedV2 = service.saveEnvironment(tempFolder, savedV1.copy(name = "Node 20 Updated"), expectedVersion = 1L)
            assertEquals(2L, savedV2.version)
            assertEquals("Node 20 Updated", service.getEnvironment(tempFolder, "node20")?.name)

            // 4. Git 忽略但显式选择的环境配置文件交集复制测试
            val includeService = WorkspaceIncludeService()
            // 初始化 git 仓库并将 .mederi/ 写入 .gitignore
            val pbInit = ProcessBuilder("git", "init").directory(tempFolder).start()
            pbInit.waitFor()

            File(tempFolder, ".gitignore").writeText(".mederi/\n")
            val pbAdd = ProcessBuilder("git", "add", ".gitignore").directory(tempFolder).start()
            pbAdd.waitFor()
            val pbCommit = ProcessBuilder("git", "commit", "-m", "ignore mederi").directory(tempFolder)
            pbCommit.environment()["GIT_AUTHOR_NAME"] = "Test"
            pbCommit.environment()["GIT_AUTHOR_EMAIL"] = "test@test.com"
            pbCommit.environment()["GIT_COMMITTER_NAME"] = "Test"
            pbCommit.environment()["GIT_COMMITTER_EMAIL"] = "test@test.com"
            pbCommit.start().waitFor()

            val candidates = includeService.computeCandidates(
                repoRoot = tempFolder.absolutePath,
                includeRulesContent = "", // worktreeinclude 为空
                selectedEnvironmentFileRel = ".mederi/environments/node20.json"
            )

            // 尽管 .mederi/ 整体被忽略，显式选定的环境配置文件仍被包含进候选集合
            assertTrue(candidates.contains(".mederi/environments/node20.json"))
        }
    }
}
