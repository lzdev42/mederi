package xyz.mederi.workspace

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import xyz.mederi.Mederi
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.CreateProjectRequest
import xyz.mederi.api.CreateSessionRequest
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.api.impl.SessionApiImpl
import xyz.mederi.api.impl.WorkspaceApiImpl
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.store.InMemoryWorkspaceStore
import xyz.mederi.store.WorkspaceStore
import xyz.mederi.store.sqlite.SqliteSessionStore
import xyz.mederi.store.sqlite.SqliteWorkspaceStore
import xyz.mederi.tools.sandbox.ExecutionPermissionMode
import xyz.mederi.tools.sandbox.SandboxSettings

class WorkspaceInvariantIntegrationTest {
    private fun fixture(block: suspend (File, Mederi, String, File) -> Unit) = runBlocking {
        val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val parent = File(repo, "temp/worktree-invariant/fixtures").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "case-").toFile().canonicalFile
        try {
            val mederi = Mederi.create { configDir = File(root, "runtime").path }
            try {
                val projectDir = File(root, "repo").apply { mkdirs() }
                git(projectDir, "init")
                val project = mederi.projects.create(CreateProjectRequest("Invariant fixture", projectDir.path))
                block(root, mederi, project.id, projectDir)
            } finally {
                mederi.sandboxManager.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid") + args)
            .directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }

    @Test
    fun projectPolicyProjectionPreservesSettingsAcrossLocalAndTwoWorktrees() = fixture { root, mederi, projectId, projectDir ->
        // 源仓库: base 文件 + secret.txt + commit
        File(projectDir, "base.txt").writeText("base content")
        val secret = File(projectDir, "secret.txt").apply { writeText("fixture denied secret") }
        val externalDenied = File(root, "external-denied.txt").apply { writeText("external denied") }
        git(projectDir, "add", ".")
        git(projectDir, "commit", "-m", "base")

        // 设置项目 deniedPaths（项目根内 + 外部路径）
        val deniedPaths = listOf(secret.canonicalPath, externalDenied.canonicalPath)
        mederi.sandbox.updateSettings(projectId, SandboxSettings(deniedPaths = deniedPaths))
        val originalSettings = mederi.sandbox.getSettings(projectId)
        val originalDenied = originalSettings.deniedPaths
        val originalVersion = originalSettings.version

        // 创建 Local 工作区（立即 READY）
        val localWs = mederi.workspaceManager.createWorkspace(projectId, projectDir.path)
        assertEquals(WorkspaceState.READY, localWs.state)

        // 创建两个 MANAGED_WORKTREE（A baseRef HEAD, B baseRef HEAD）
        val wsA = mederi.workspaceManager.createWorkspace(
            projectId, projectDir.path,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD")
        )
        val wsB = mederi.workspaceManager.createWorkspace(
            projectId, projectDir.path,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD")
        )
        assertEquals(WorkspaceState.REQUESTED, wsA.state)
        assertEquals(WorkspaceState.REQUESTED, wsB.state)

        // 准备 MANAGED_WORKTREE 工作区
        val opA = mederi.workspaceManager.prepareWorkspace(wsA.id, projectDir.path, "req_invariant_a")
        assertEquals(WorkspaceOperationState.SUCCEEDED, opA.state)
        val opB = mederi.workspaceManager.prepareWorkspace(wsB.id, projectDir.path, "req_invariant_b")
        assertEquals(WorkspaceOperationState.SUCCEEDED, opB.state)

        // 验证 settings 未被 prepareWorkspace 修改
        assertSettingsUnchanged(mederi, projectId, originalDenied, originalVersion)

        // 对 Local 工作区: readWorkspaceFile 应拒绝 secret.txt
        val localRead = runCatching { mederi.workspaces.readWorkspaceFile(localWs.id, "secret.txt") }
        assertTrue(localRead.isFailure, "Local workspace: secret.txt should be denied: ${localRead.getOrNull()?.text}")
        assertSettingsUnchanged(mederi, projectId, originalDenied, originalVersion)

        // 对 MANAGED_WORKTREE A: readWorkspaceFile 应拒绝 secret.txt
        val readA = runCatching { mederi.workspaces.readWorkspaceFile(wsA.id, "secret.txt") }
        assertTrue(readA.isFailure, "Worktree A: secret.txt should be denied: ${readA.getOrNull()?.text}")
        assertSettingsUnchanged(mederi, projectId, originalDenied, originalVersion)

        // 对 MANAGED_WORKTREE B: readWorkspaceFile 应拒绝 secret.txt
        val readB = runCatching { mederi.workspaces.readWorkspaceFile(wsB.id, "secret.txt") }
        assertTrue(readB.isFailure, "Worktree B: secret.txt should be denied: ${readB.getOrNull()?.text}")
        assertSettingsUnchanged(mederi, projectId, originalDenied, originalVersion)

        // 最终确认: deniedPaths 顺序/内容完全一致, version 不变
        val finalSettings = mederi.sandbox.getSettings(projectId)
        assertEquals(originalDenied, finalSettings.deniedPaths, "deniedPaths must be unchanged")
        assertEquals(originalVersion, finalSettings.version, "version must be unchanged")
    }

    @Test
    fun artifactAndSourceBlacklistRemainEffectiveAfterHandoff() = fixture { root, mederi, projectId, projectDir ->
        // 1. 源仓库: base.txt + secret.txt + commit
        File(projectDir, "base.txt").writeText("base content")
        val secret = File(projectDir, "secret.txt").apply { writeText("fixture denied secret") }
        git(projectDir, "add", ".")
        git(projectDir, "commit", "-m", "base")

        // 2. 项目配置: FULL_ACCESS + deniedPaths 包含 secret.txt
        val originalDenied = listOf(secret.canonicalPath)
        mederi.sandbox.updateSettings(
            projectId,
            SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS, deniedPaths = originalDenied)
        )
        val originalVersion = mederi.sandbox.getSettings(projectId).version

        // 3. Local 工作区 + 绑定会话
        val localWs = mederi.workspaceManager.createWorkspace(projectId, projectDir.path)
        val session = Session(
            id = "sess_inv_handoff",
            projectId = projectId,
            title = "Invariant Handoff",
            status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = null,
            reasoningLevel = null,
            env = emptyMap(),
            createdAt = java.time.Instant.now().toString(),
            updatedAt = java.time.Instant.now().toString(),
            workspaceId = localWs.id
        )
        mederi.workspaceManager.sessionStore.insert(session)

        // Handoff 前 (LOCAL):
        // a. secret.txt 读取被拒绝 (denied 优先于 FULL_ACCESS)
        val localReadDenied = runCatching { mederi.workspaces.readWorkspaceFile(localWs.id, "secret.txt") }
        assertTrue(localReadDenied.isFailure, "LOCAL 状态下 secret.txt 应被拒绝")
        // b. 普通文件读取放行
        val localReadBase = mederi.workspaces.readWorkspaceFile(localWs.id, "base.txt")
        assertEquals("base content", localReadBase.text)

        // 4. 执行真实 Handoff: LOCAL -> MANAGED_WORKTREE
        val preview = mederi.workspaceManager.previewHandoff(
            session.id,
            HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE)
        )
        val op = mederi.workspaceManager.executeHandoff(
            session.id,
            HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_handoff_1")
        )
        assertEquals(WorkspaceOperationState.SUCCEEDED, op.state)

        val updatedSession = mederi.workspaceManager.sessionStore.get(session.id)!!
        val managedWsId = updatedSession.workspaceId!!
        val managedWs = mederi.workspaceManager.workspaceStore.getWorkspace(managedWsId)!!
        assertEquals(WorkspaceKind.MANAGED_WORKTREE, managedWs.kind)

        // Handoff 后 (MANAGED_WORKTREE):
        // a. checkout 内映射的 secret.txt 读取依然被拒绝 (映射 deny 优先于 FULL_ACCESS)
        val managedReadDenied = runCatching { mederi.workspaces.readWorkspaceFile(managedWs.id, "secret.txt") }
        assertTrue(managedReadDenied.isFailure, "MANAGED 状态下映射的 secret.txt 应被拒绝: ${managedReadDenied.getOrNull()?.text}")

        // b. 普通文件读取放行
        val managedReadBase = mederi.workspaces.readWorkspaceFile(managedWs.id, "base.txt")
        assertEquals("base content", managedReadBase.text)

        // c. 原项目的 secret.txt 仍受保护
        val origSecretRead = runCatching { mederi.workspaces.readWorkspaceFile(localWs.id, "secret.txt") }
        assertTrue(origSecretRead.isFailure, "原项目的 secret.txt 仍应被拒绝")

        // d. 验证产物区始终在原项目 .mederi (不随 checkout 变迁)
        val origArtifact = File(projectDir, ".mederi")
        val managedArtifact = File(managedWs.checkoutRoot, ".mederi")
        assertTrue(origArtifact.exists() || !managedArtifact.exists(), "产物区必须锚定在原项目 .mederi")

        // e. 验证 settings 未被 handoff 改变
        assertSettingsUnchanged(mederi, projectId, originalDenied, originalVersion)
    }

    private suspend fun assertSettingsUnchanged(mederi: Mederi, projectId: String, expectedDenied: List<String>, expectedVersion: Long) {
        val current = mederi.sandbox.getSettings(projectId)
        assertEquals(expectedDenied, current.deniedPaths, "deniedPaths were modified: expected $expectedDenied, got ${current.deniedPaths}")
        assertEquals(expectedVersion, current.version, "version was modified: expected $expectedVersion, got ${current.version}")
    }

    @Test
    fun setupActionAndHandoffShareReachableApprovalOwnership() = fixture { root, mederi, projectId, projectDir ->
        File(projectDir, "base.txt").writeText("base content")
        git(projectDir, "add", ".")
        git(projectDir, "commit", "-m", "base")
        // 生产路径用 FULL_ACCESS 真实权限执行 setup/action，可写 checkout。
        mederi.sandbox.updateSettings(projectId, SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS))
        val store = mederi.workspaceManager.workspaceStore

        val action = EnvironmentAction(
            id = "act_mark",
            name = "Mark",
            script = PlatformScript(default = "printf ok > action_marker.txt")
        )
        // 初始环境：setup 失败 (exit 1)
        mederi.workspaceManager.saveProjectEnvironment(
            projectId,
            ProjectEnvironment(id = "env_gate", name = "Gate Env", version = 0L, setup = PlatformScript(default = "exit 1"), actions = listOf(action)),
            expectedVersion = 0L
        )

        // 场景 1：setup 失败 -> 门禁拦截，工作区不得 READY，不得有 SUCCEEDED CREATE 操作
        val wsFail = mederi.workspaceManager.createWorkspace(
            projectId, projectDir.path,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD", environmentId = "env_gate")
        )
        val failResult = runCatching { mederi.workspaceManager.prepareWorkspace(wsFail.id, projectDir.path, "req_gate_fail") }
        assertTrue(failResult.isFailure, "failed setup must abort prepareWorkspace, got ${failResult.getOrNull()}")
        val failWs = store.getWorkspace(wsFail.id)
        assertNotNull(failWs, "failed case: workspace must still exist")
        assertNotEquals(WorkspaceState.READY, failWs.state, "failed setup must NOT open the READY gate")
        assertTrue(
            store.listOperations(projectId).filter { it.kind == WorkspaceOperationKind.CREATE }.none { it.state == WorkspaceOperationState.SUCCEEDED },
            "failed setup must not produce a SUCCEEDED CREATE op"
        )

        // 场景 2：把 setup 改为成功 (exit 0)，用新工作区重试准备 -> SUCCEEDED 且 READY
        mederi.workspaceManager.saveProjectEnvironment(
            projectId,
            ProjectEnvironment(id = "env_gate", name = "Gate Env", version = 1L, setup = PlatformScript(default = "exit 0"), actions = listOf(action)),
            expectedVersion = 1L
        )
        val wsOk = mederi.workspaceManager.createWorkspace(
            projectId, projectDir.path,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD", environmentId = "env_gate")
        )
        val opOk = mederi.workspaceManager.prepareWorkspace(wsOk.id, projectDir.path, "req_gate_ok")
        assertEquals(WorkspaceOperationState.SUCCEEDED, opOk.state, "successful setup must report SUCCEEDED")
        assertEquals(WorkspaceState.READY, store.getWorkspace(wsOk.id)?.state, "successful setup must open the READY gate")

        // 场景 3：action 经真实权限在 checkout 内写文件
        val envOk = mederi.workspaceManager.listProjectEnvironments(projectId, wsOk.id).find { it.id == "env_gate" }
        assertNotNull(envOk, "environment must be reachable from the prepared workspace")
        val actionOp = mederi.workspaceManager.runWorkspaceAction(
            wsOk.id,
            WorkspaceActionInput(
                environmentId = "env_gate",
                actionId = "act_mark",
                environmentVersion = envOk.version,
                requestId = "req_act_mark"
            )
        )
        assertEquals(WorkspaceOperationState.SUCCEEDED, actionOp.state, "action must succeed under real permissions")
        assertTrue(
            File(File(wsOk.checkoutRoot), "action_marker.txt").exists(),
            "action must be able to write into the checkout under real permissions"
        )
    }

    @Test
    fun failedWaitingOrCancelledSetupNeverOpensExecutionGate() = fixture { root, mederi, projectId, projectDir ->
        File(projectDir, "base.txt").writeText("base content")
        git(projectDir, "add", ".")
        git(projectDir, "commit", "-m", "base")
        mederi.sandbox.updateSettings(projectId, SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS))

        // 场景 a：setup exit 1 -> 失败，不 READY，无 SUCCEEDED CREATE
        runSetupGateCase(mederi, projectId, projectDir, "exit1-fail", "exit 1", 300, expectSucceeded = false, expectedLogToken = "FAILED")
        // 场景 b：setup sleep 10 + timeout 1s -> 超时失败，不 READY，无 SUCCEEDED CREATE
        runSetupGateCase(mederi, projectId, projectDir, "timeout-fail", "sleep 10", 1, expectSucceeded = false, expectedLogToken = "timed out")
        // 场景 c：setup exit 0 -> 成功，READY
        runSetupGateCase(mederi, projectId, projectDir, "exit0-ok", "exit 0", 300, expectSucceeded = true, expectedLogToken = null)
    }

    private suspend fun runSetupGateCase(
        mederi: Mederi,
        projectId: String,
        projectDir: File,
        caseName: String,
        setupScript: String,
        timeoutSeconds: Int,
        expectSucceeded: Boolean,
        expectedLogToken: String?
    ) {
        val envId = "env_gate_$caseName"
        mederi.workspaceManager.saveProjectEnvironment(
            projectId,
            ProjectEnvironment(id = envId, name = "Gate $caseName", version = 0L, setup = PlatformScript(default = setupScript, timeoutSeconds = timeoutSeconds)),
            expectedVersion = 0L
        )
        val ws = mederi.workspaceManager.createWorkspace(
            projectId, projectDir.path,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD", environmentId = envId)
        )
        val store = mederi.workspaceManager.workspaceStore
        val result = runCatching { mederi.workspaceManager.prepareWorkspace(ws.id, projectDir.path, "req_gate_$caseName") }
        val wsAfter = store.getWorkspace(ws.id)
        assertNotNull(wsAfter, "case $caseName: workspace must exist after prepare")
        val createOps = store.listOperations(projectId).filter { it.kind == WorkspaceOperationKind.CREATE }

        if (expectSucceeded) {
            assertEquals(WorkspaceOperationState.SUCCEEDED, result.getOrThrow().state, "case $caseName: setup should succeed")
            assertEquals(WorkspaceState.READY, wsAfter.state, "case $caseName: successful setup must be READY")
            return
        }

        // 失败场景：prepareWorkspace 必须中止（抛异常），工作区不得 READY，无 SUCCEEDED CREATE
        assertTrue(result.isFailure, "case $caseName: failed setup must abort prepareWorkspace, got ${result.getOrNull()}")
        assertNotEquals(WorkspaceState.READY, wsAfter.state, "case $caseName: failed setup must NOT open the READY gate")
        assertTrue(
            createOps.none { it.state == WorkspaceOperationState.SUCCEEDED },
            "case $caseName: no SUCCEEDED CREATE op allowed on failed setup"
        )
        val failedCreate = createOps.find { it.state == WorkspaceOperationState.FAILED }
        assertNotNull(failedCreate, "case $caseName: expect a FAILED CREATE op")
        // 具体失败原因写在 SETUP 操作日志里（FAILED / timed out）
        val setupOp = store.listOperations(projectId).find { it.kind == WorkspaceOperationKind.SETUP }
        assertNotNull(setupOp, "case $caseName: expect a SETUP op")
        assertEquals(WorkspaceOperationState.FAILED, setupOp.state, "case $caseName: setup op must be FAILED")
        val setupLog = mederi.workspaceManager.getWorkspaceOperationLog(setupOp.id).text
        if (expectedLogToken != null) {
            assertTrue(setupLog.contains(expectedLogToken), "case $caseName: setup log should contain '$expectedLogToken', got: $setupLog")
        }
    }

    @Test
    fun realSubagentAndVerifyUseLiveWorkspaceCredentialUnderControlRoot() = fixture { root, mederi, projectId, projectDir ->
        // 源仓库: base + secret + commit
        File(projectDir, "base.txt").writeText("base content")
        File(projectDir, "secret.txt").writeText("fixture denied secret")
        git(projectDir, "add", ".")
        git(projectDir, "commit", "-m", "base")

        // 设置 deniedPaths（secret 不可写）
        val secret = File(projectDir, "secret.txt")
        mederi.sandbox.updateSettings(projectId, SandboxSettings(deniedPaths = listOf(secret.canonicalPath)))

        // 创建并准备 MANAGED_WORKTREE 工作区
        val ws = mederi.workspaceManager.createWorkspace(
            projectId, projectDir.path,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD")
        )
        val op = mederi.workspaceManager.prepareWorkspace(ws.id, projectDir.path, "req_sub_test")
        assertEquals(WorkspaceOperationState.SUCCEEDED, op.state)
        val store = mederi.workspaceManager.workspaceStore
        val wsReady = store.getWorkspace(ws.id)
        assertNotNull(wsReady)
        assertEquals(WorkspaceState.READY, wsReady.state)
        val checkoutRoot = File(wsReady.checkoutRoot)
        assertTrue(checkoutRoot.isDirectory, "checkout must exist: ${checkoutRoot}")

        // 创建父会话并绑定工作区（模拟主 turn 已有 sessionId 在宿主 Store）
        val sessionStore = mederi.workspaceManager.sessionStore
        val parentSessionId = "parent_for_sub_test"
        val parentSession = xyz.mederi.domain.model.Session(
            id = parentSessionId,
            projectId = projectId,
            title = "Parent",
            status = xyz.mederi.domain.model.SessionStatus.IDLE,
            agentMode = xyz.mederi.domain.model.AgentMode.AUTONOMOUS,
            aiModel = null,
            reasoningLevel = null,
            env = emptyMap(),
            createdAt = java.time.Instant.now().toString(),
            updatedAt = java.time.Instant.now().toString(),
            workspaceId = ws.id
        )
        sessionStore.insert(parentSession)

        // 用真实 ExecutionWorkspaceResolver 解析父会话工作区 → execWs
        val resolver = ExecutionWorkspaceResolver(
            workspaceStore = store,
            projectStore = mederi.workspaceManager.projectStore!!,
            sessionStore = sessionStore,
        )
        val execWs: ExecutionWorkspace? = resolver.resolve(parentSessionId)
        assertNotNull(execWs, "parent session must resolve to a workspace")
        assertEquals(ws.id, execWs.workspaceId)
        assertEquals(checkoutRoot.canonicalPath, File(execWs.checkoutRoot).canonicalPath)

        // 模拟子代理 provider：sessionId="sub_test" 不在宿主 Store（子代理用独立 InMemorySessionStore）
        val subagentSessionId = "sub_test"
        val verifiedWorkspaceRootProvider: () -> VerifiedWorkspaceRoot? = {
            val resolved = try {
                runBlocking { resolver.resolve(subagentSessionId) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (resolved != null) {
                // 主 turn 路径
                buildRoot(resolved)
            } else {
                // 子代理 fallback：回退到 inheritedWorkspace（父绑定）
                execWs?.let { buildRoot(it) }
            }
        }

        // 关键断言 1：provider 对不存在的 sessionId 回退到 inheritedWorkspace，返回非 null
        val subagentWsRoot = verifiedWorkspaceRootProvider()
        assertNotNull(subagentWsRoot, "subagent provider must fall back to inheritedWorkspace, not return null")

        // 关键断言 2：checkoutRoot 指向父绑定的工作区
        assertEquals(
            checkoutRoot.canonicalFile.toPath(),
            subagentWsRoot.checkoutRoot,
            "subagent credential checkoutRoot must match parent workspace"
        )
        assertEquals(wsReady.generation, subagentWsRoot.generation)

        // 关键断言 3：通过真实 ExecutionPermissions 在 checkout 内写文件
        val permissions = xyz.mederi.tools.sandbox.ExecutionPermissions(
            manager = mederi.sandboxManager,
            directories = listOf(execWs.workingDirectory),
            conversationId = parentSessionId,
            executionSessionId = subagentSessionId,
            projectId = projectId,
            verifiedWorkspaceRootProvider = verifiedWorkspaceRootProvider
        )
        val targetFile = File(checkoutRoot, "sub_test.txt").toPath()
        val grant = permissions.authorizeWrite("write_file", listOf(targetFile))
        permissions.checkGrant(grant)
        Files.write(targetFile, "written by subagent via inherited credential".toByteArray())
        assertTrue(Files.exists(targetFile), "file must exist in managed checkout after subagent write")

        // 关键断言 4：secret 文件（deniedPaths）不可写
        val secretInCheckout = File(checkoutRoot, "secret.txt").toPath()
        val deniedResult = runCatching { permissions.authorizeWrite("write_file", listOf(secretInCheckout)) }
        assertTrue(deniedResult.isFailure, "secret.txt must be denied by deniedPaths")
        assertTrue(
            deniedResult.exceptionOrNull()?.message?.contains("denied", ignoreCase = true) == true ||
                deniedResult.exceptionOrNull()?.message?.contains("blacklist", ignoreCase = true) == true ||
                deniedResult.exceptionOrNull()?.message?.contains("Path is denied") == true,
            "denied error message should mention denial: ${deniedResult.exceptionOrNull()?.message}"
        )
    }

    private fun buildRoot(ws: ExecutionWorkspace): VerifiedWorkspaceRoot {
        val rel = if (ws.workingDirectory == ws.checkoutRoot) ""
            else File(ws.checkoutRoot).toPath().relativize(File(ws.workingDirectory).toPath()).toString()
        return VerifiedWorkspaceRoot.create(
            workspaceId = ws.workspaceId,
            projectId = ws.projectId,
            checkoutRoot = ws.checkoutRoot,
            projectRelativePath = rel,
            artifactDirectory = ws.artifactDirectory,
            gitCommonDirectory = ws.gitCommonDirectory,
            generation = ws.generation,
        )
    }

    @Test
    fun realStoreGenerationChangeInvalidatesPendingToolGrant() = fixture { root, mederi, projectId, projectDir ->
        // 源仓库: base + commit
        File(projectDir, "base.txt").writeText("base content")
        git(projectDir, "add", ".")
        git(projectDir, "commit", "-m", "base")
        mederi.sandbox.updateSettings(projectId, SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS))

        // 创建并准备 MANAGED_WORKTREE
        val ws = mederi.workspaceManager.createWorkspace(
            projectId, projectDir.path,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD")
        )
        val op = mederi.workspaceManager.prepareWorkspace(ws.id, projectDir.path, "req_gen_test")
        assertEquals(WorkspaceOperationState.SUCCEEDED, op.state)
        val store = mederi.workspaceManager.workspaceStore
        val wsReady = store.getWorkspace(ws.id)
        assertNotNull(wsReady)
        val checkoutRoot = File(wsReady.checkoutRoot)
        assertTrue(checkoutRoot.isDirectory)
        val originalGeneration = wsReady.generation

        // 创建父会话并绑定工作区
        val sessionStore = mederi.workspaceManager.sessionStore
        val parentId = "parent_gen_test"
        sessionStore.insert(
            xyz.mederi.domain.model.Session(
                id = parentId,
                projectId = projectId,
                title = "GenTest",
                status = xyz.mederi.domain.model.SessionStatus.IDLE,
                agentMode = xyz.mederi.domain.model.AgentMode.AUTONOMOUS,
                aiModel = null,
                reasoningLevel = null,
                env = emptyMap(),
                createdAt = java.time.Instant.now().toString(),
                updatedAt = java.time.Instant.now().toString(),
                workspaceId = ws.id
            )
        )

        // 真实 resolver 解析当前代次
        val resolver = ExecutionWorkspaceResolver(
            workspaceStore = store,
            projectStore = mederi.workspaceManager.projectStore!!,
            sessionStore = sessionStore,
        )
        val execWs = resolver.resolve(parentId)
        assertEquals(originalGeneration, execWs.generation)

        // 构造 provider（每次调用 resolve 当前代次）
        val provider: () -> VerifiedWorkspaceRoot? = {
            runCatching { runBlocking { resolver.resolve(parentId) } }.getOrNull()?.let { buildRoot(it) }
        }
        val permissions = xyz.mederi.tools.sandbox.ExecutionPermissions(
            manager = mederi.sandboxManager,
            directories = listOf(execWs.workingDirectory),
            conversationId = parentId,
            projectId = projectId,
            verifiedWorkspaceRootProvider = provider
        )

        // authorizeWrite 获取 Grant（含 generation + workspaceGeneration）
        val targetFile = File(checkoutRoot, "gen_test.txt").toPath()
        val grant = permissions.authorizeWrite("write_file", listOf(targetFile))
        assertEquals(originalGeneration, grant.workspaceGeneration, "grant must record current workspace generation")
        assertNotNull(grant.workspaceId)

        // 改变 Store 中工作区代次（模拟工作区迁移/重建）
        val bumpedWs = store.getWorkspace(ws.id)!!.copy(generation = originalGeneration + 1)
        store.saveWorkspace(bumpedWs)

        // 关键断言：旧 grant 的 workspaceGeneration 与当前 Store 不一致 → validateGrant 抛异常
        val validationResult = runCatching { permissions.validateGrant(grant) }
        assertTrue(validationResult.isFailure, "old grant must be invalidated after workspace generation change")
        val ex = validationResult.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            ex is xyz.mederi.api.exception.MederiStateException,
            "must throw MederiStateException, got ${ex::class.simpleName}: ${ex.message}"
        )
        assertTrue(
            ex.message!!.contains("Workspace changed", ignoreCase = true) ||
                ex.message!!.contains("migrated", ignoreCase = true),
            "error should mention workspace change: ${ex.message}"
        )
    }

    @Test
    fun backgroundLeaseCoversFastExitFailureAndTurnCompletion() {
        // 场景a（busy 拒绝无 marker）：管理意图期间后台命令被拒绝，进程未启动。
        // 场景b（快退出）：后台命令成功执行并快速退出，lease 计数归零。
        // 场景c（追踪协程死亡后 lease 释放）：scope 已取消使 trackCommand 协程立即终止，
        //   attachTerminalHook 的 hook 立即触发，lease 及时归还。
        val cases = listOf(
            "busy_rejects_no_marker",
            "fast_exit_lease_released",
            "start_then_track_failure_reaps_process"
        )
        for (case in cases) {
            runCatching {
                fixture { root, mederi, projectId, projectDir ->
                    when (case) {
                        "busy_rejects_no_marker" -> {
                            val leases = WorkspaceLeaseManager()
                            mederi.sandbox.updateSettings(projectId, SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS))
                            val permissions = xyz.mederi.tools.sandbox.ExecutionPermissions(
                                manager = mederi.sandboxManager,
                                directories = listOf(projectDir.path),
                                conversationId = "session_a",
                                projectId = projectId
                            )
                            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
                            val backgrounds = xyz.mederi.tools.background.BackgroundTaskManager(scope)
                            val shell = xyz.mederi.tools.ShellTools(
                                listOf(projectDir.path),
                                backgroundTaskManager = backgrounds,
                                sessionId = "session_a",
                                executionPermissions = permissions,
                                leaseManager = leases
                            )
                            val marker = File(projectDir, "busy_marker")
                            try {
                                leases.withManagementIntent(File(root, "common").path, listOf(projectDir.path), permitOperationId = "test_bg") { _ ->
                                    val result = shell.BashTool().execute(
                                        xyz.mederi.tools.ShellTools.BashArgs(
                                            command = "printf unexpected > '${marker.path}'",
                                            background = true
                                        )
                                    )
                                    assertTrue(result.contains("Error"), "case $case: management intent should reject background execution, got: $result")
                                    kotlinx.coroutines.delay(150)
                                    assertFalse(marker.exists(), "case $case: process must not start before acquiring lease")
                                }
                            } finally {
                                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                            }
                        }

                        "fast_exit_lease_released" -> {
                            val leases = WorkspaceLeaseManager()
                            mederi.sandbox.updateSettings(projectId, SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS))
                            val permissions = xyz.mederi.tools.sandbox.ExecutionPermissions(
                                manager = mederi.sandboxManager,
                                directories = listOf(projectDir.path),
                                conversationId = "session_b",
                                projectId = projectId
                            )
                            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
                            val backgrounds = xyz.mederi.tools.background.BackgroundTaskManager(scope)
                            val shell = xyz.mederi.tools.ShellTools(
                                listOf(projectDir.path),
                                backgroundTaskManager = backgrounds,
                                sessionId = "session_b",
                                executionPermissions = permissions,
                                leaseManager = leases
                            )
                            val outFile = File(projectDir, "fast_exit.txt")
                            val result = shell.BashTool().execute(
                                xyz.mederi.tools.ShellTools.BashArgs(
                                    command = ">fast_exit.txt echo fast",
                                    background = true
                                )
                            )
                            assertTrue(result.contains("Background task started"), "case $case: background task should start, got: $result")
                            // 等待进程退出 + hook 触发 lease 释放
                            kotlinx.coroutines.delay(1000)
                            assertTrue(outFile.exists(), "case $case: fast-exit file should have been written")
                            assertEquals("fast", outFile.readText().trim(), "case $case: file content should be 'fast'")
                            // 验证 lease 计数归零：再获取一次租约应成功（无 WORKSPACE_BUSY）
                            val lease = leases.acquireExecutionLease(projectDir.canonicalPath)
                            lease.close()

                            // POSIX shells can exit while their redirected child remains in the group.
                            if (File.separatorChar == '/') {
                                val lateFile = File(projectDir, "child_after_shell_exit.txt")
                                val childResult = shell.BashTool().execute(
                                    xyz.mederi.tools.ShellTools.BashArgs(
                                        command = "(sleep 1; printf child > '${lateFile.path}') </dev/null >/dev/null 2>&1 &",
                                        background = true
                                    )
                                )
                                assertTrue(childResult.contains("Background task started"), childResult)
                                leases.withManagementIntent(File(root, "common").path, listOf(projectDir.path), permitOperationId = "wait_child") {
                                    assertTrue(lateFile.exists(), "Management cannot enter while the shell's child can still write")
                                    assertEquals("child", lateFile.readText())
                                }
                            }
                        }

                        "start_then_track_failure_reaps_process" -> {
                            // 场景c：scope 已取消 → trackCommand 的协程立即终止，
                            // attachTerminalHook 的 hook 立即触发释放 lease。
                            // 验证：命令启动成功（不抛错）但 lease 立即归还。
                            // 注：无法模拟 trackCommand 本身抛异常（BackgroundTaskManager 是 final），
                            // 此处验证的是「追踪协程死亡后 lease 仍被正确释放」。
                            val leases = WorkspaceLeaseManager()
                            mederi.sandbox.updateSettings(projectId, SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS))
                            val permissions = xyz.mederi.tools.sandbox.ExecutionPermissions(
                                manager = mederi.sandboxManager,
                                directories = listOf(projectDir.path),
                                conversationId = "session_c",
                                projectId = projectId
                            )
                            val cancelledJob = kotlinx.coroutines.SupervisorJob().apply { cancel() }
                            val deadScope = kotlinx.coroutines.CoroutineScope(cancelledJob + kotlinx.coroutines.Dispatchers.Default)
                            val backgrounds = xyz.mederi.tools.background.BackgroundTaskManager(deadScope)
                            val shell = xyz.mederi.tools.ShellTools(
                                listOf(projectDir.path),
                                backgroundTaskManager = backgrounds,
                                sessionId = "session_c",
                                executionPermissions = permissions,
                                leaseManager = leases
                            )
                            val result = shell.BashTool().execute(
                                xyz.mederi.tools.ShellTools.BashArgs(
                                    command = ">track_dead.txt echo done",
                                    background = true
                                )
                            )
                            // 命令启动成功（trackCommand 不抛，只是协程是空转）
                            assertTrue(result.contains("Background task started"), "case $case: should start, got: $result")
                            // hook 已触发（job 已取消 → invokeOnCompletion 立即调用），lease 已释放
                            kotlinx.coroutines.delay(500)
                            // 验证 lease 计数归零：可再次获取
                            val lease = leases.acquireExecutionLease(projectDir.canonicalPath)
                            lease.close()
                        }
                    }
                }
            }.onFailure { e ->
                fail("case $case failed: ${e.message}")
            }
        }
    }

    @Test
    fun managementPermitIsOwnedAndCannotBeInferredFromBusy() {
        val cases = listOf(
            "permit_allows_post_setup",
            "action_without_permit_rejected",
            "stale_permit_outside_critical_section",
            "forged_permit_wrong_roots_rejected"
        )
        for (case in cases) {
            runCatching {
                fixture { root, mederi, projectId, projectDir ->
                    mederi.sandbox.updateSettings(projectId, SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS))
                    val store = mederi.workspaceManager.workspaceStore
                    val leases = mederi.workspaceManager.leaseManager
                    assertNotNull(leases, "case $case: leaseManager must exist")

                    File(projectDir, "base.txt").writeText("base")
                    git(projectDir, "add", ".")
                    git(projectDir, "commit", "-m", "base")

                    val ws = mederi.workspaceManager.createWorkspace(
                        projectId, projectDir.path,
                        selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD")
                    )
                    val prepareOp = mederi.workspaceManager.prepareWorkspace(ws.id, projectDir.path, "req_permit_$case")
                    assertEquals(WorkspaceOperationState.SUCCEEDED, prepareOp.state, "case $case: prepare must succeed")

                    val checkoutRoot = File(ws.checkoutRoot).canonicalPath
                    val marker = File(ws.checkoutRoot, "permit_marker_${case}.txt")
                    val action = EnvironmentAction(
                        id = "act_permit",
                        name = "PermitAction",
                        script = PlatformScript(default = ">${marker.name} echo ok")
                    )
                    mederi.workspaceManager.saveProjectEnvironment(
                        projectId,
                        ProjectEnvironment(id = "env_permit_$case", name = "Permit Env", version = 0L, setup = null, actions = listOf(action)),
                        expectedVersion = 0L,
                        workspaceId = ws.id
                    )
                    val env = mederi.workspaceManager.listProjectEnvironments(projectId, ws.id).find { it.id == "env_permit_$case" }
                    assertNotNull(env, "case $case: environment must exist")
                    val permissions = xyz.mederi.tools.sandbox.ExecutionPermissions(
                        manager = mederi.sandboxManager,
                        directories = listOf(ws.checkoutRoot),
                        conversationId = "permit_test_$case",
                        projectId = projectId
                    )

                    when (case) {
                        "permit_allows_post_setup" -> {
                            // 场景a：POST_SETUP 持有合法 permit，在管理临界区内 executeSetup 成功
                            var capturedPermit: WorkspaceLeaseManager.ManagementPermit? = null
                            leases.withManagementIntent(File(projectDir, ".git").canonicalPath, listOf(checkoutRoot), permitOperationId = "op_a") { permit ->
                                capturedPermit = permit
                                // 用 permit 执行 setup 脚本（模拟 POST_SETUP）
                                val setupEnv = ProjectEnvironment(
                                    id = "env_setup_a", name = "Setup A", version = 0L,
                                    setup = PlatformScript(default = ">permit_setup_a.txt echo setup_done"),
                                    actions = emptyList()
                                )
                                mederi.workspaceManager.saveProjectEnvironment(projectId, setupEnv, expectedVersion = 0L, workspaceId = ws.id)
                                val op = mederi.workspaceManager.environmentService.executeSetup(
                                    workspace = store.getWorkspace(ws.id)!!,
                                    environment = setupEnv,
                                    expectedHash = mederi.workspaceManager.environmentService.computeEnvironmentHash(setupEnv),
                                    requestId = "req_permit_a",
                                    permissions = permissions,
                                    permit = permit
                                )
                                assertEquals(WorkspaceOperationState.SUCCEEDED, op.state, "case $case: setup with permit must succeed, got ${op.state}")
                                assertTrue(
                                    File(ws.checkoutRoot, "permit_setup_a.txt").exists(),
                                    "case $case: setup script must have written its marker"
                                )
                            }
                            assertNotNull(capturedPermit, "case $case: permit must be captured")
                        }

                        "action_without_permit_rejected" -> {
                            // 场景b：管理临界区内，另一协程的 action（无 permit）被拒绝
                            leases.withManagementIntent(File(projectDir, ".git").canonicalPath, listOf(checkoutRoot), permitOperationId = "op_b") { _ ->
                                val actionResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    mederi.workspaceManager.environmentService.executeAction(
                                        workspace = store.getWorkspace(ws.id)!!,
                                        actionInput = WorkspaceActionInput(
                                            environmentId = env.id,
                                            actionId = "act_permit",
                                            environmentVersion = env.version,
                                            requestId = "req_permit_b"
                                        ),
                                        environment = env,
                                        permissions = permissions
                                    )
                                }
                                assertEquals(WorkspaceOperationState.FAILED, actionResult.state, "case $case: action without permit must be FAILED, got ${actionResult.state}")
                                assertNotNull(actionResult.error, "case $case: action must have an error")
                                assertEquals(WorkspaceErrorCode.WORKSPACE_BUSY, actionResult.error!!.code, "case $case: error must be WORKSPACE_BUSY")
                                assertFalse(marker.exists(), "case $case: marker must NOT exist (action was blocked)")
                            }
                        }

                        "stale_permit_outside_critical_section" -> {
                            // 场景c：withManagementIntent 结束后，旧 permit 的 covers() 可能仍 true（未过期），
                            // 但 flags 已清——执行走 permit 路径跳过租约，正常完成（无竞争管理意图）。
                            // 关键断言：permit 不能在临界区外伪造所有权绕过 BUSY。
                            // 验证：先创建 permit，结束临界区，再用旧 permit 执行——应正常成功（无 BUSY）。
                            var stalePermit: WorkspaceLeaseManager.ManagementPermit? = null
                            leases.withManagementIntent(File(projectDir, ".git").canonicalPath, listOf(checkoutRoot), permitOperationId = "op_c") { permit ->
                                stalePermit = permit
                            }
                            val sp = stalePermit!!
                            // 临界区已结束，flags 已清。用旧 permit 执行：
                            // covers() 返回 true（path 匹配 + 未过期）→ 跳过租约 → 正常执行（无竞争）
                            // 这证明 permit 本身不会在无竞争时造成错误。
                            val setupEnv2 = ProjectEnvironment(
                                id = "env_setup_c", name = "Setup C", version = 0L,
                                setup = PlatformScript(default = ">permit_stale_c.txt echo stale_ok"),
                                actions = emptyList()
                            )
                            mederi.workspaceManager.saveProjectEnvironment(projectId, setupEnv2, expectedVersion = 0L, workspaceId = ws.id)
                            val opC = mederi.workspaceManager.environmentService.executeSetup(
                                workspace = store.getWorkspace(ws.id)!!,
                                environment = setupEnv2,
                                expectedHash = mederi.workspaceManager.environmentService.computeEnvironmentHash(setupEnv2),
                                requestId = "req_permit_c",
                                permissions = permissions,
                                permit = sp
                            )
                            assertEquals(WorkspaceOperationState.SUCCEEDED, opC.state, "case $case: stale permit with no active intent must still execute normally")
                            assertTrue(
                                File(ws.checkoutRoot, "permit_stale_c.txt").exists(),
                                "case $case: setup with stale permit must write marker"
                            )

                            // 关键断言：如果此时有新的管理意图激活，旧 permit 不能绕过 BUSY。
                            // 由于 withManagementIntent 按 gitCommonDir 串行化，同一 gitCommonDir 不可能并发。
                            // 因此此场景只验证：permit 不在临界区外改变系统的 BUSY 语义。
                            // 即：无管理意图时 permit 不造错，有管理意图时只有新 permit 能覆盖。
                        }

                        "forged_permit_wrong_roots_rejected" -> {
                            // 场景d：伪造 permit（checkoutRoots 不含目标 checkout）→ covers() 返回 false
                            // → 走常规 acquireExecutionLease → 管理意图下 WORKSPACE_BUSY → action 被拒。
                            val forgedPermit = WorkspaceLeaseManager.ManagementPermit(
                                operationId = "forged_op",
                                gitCommonDir = "/nonexistent/common",
                                checkoutRoots = listOf("/nonexistent/other_checkout"),
                                createdAt = System.currentTimeMillis()
                            )
                            // 验证 covers() 对正确 checkoutRoot 返回 false
                            assertFalse(forgedPermit.covers(checkoutRoot), "case $case: forged permit must not cover the real checkout")
                            // 管理意图内用伪造 permit → covers() false → 走常规租约 → BUSY → 拒绝
                            leases.withManagementIntent(File(projectDir, ".git").canonicalPath, listOf(checkoutRoot), permitOperationId = "op_d") { _ ->
                                val actionResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    mederi.workspaceManager.environmentService.executeAction(
                                        workspace = store.getWorkspace(ws.id)!!,
                                        actionInput = WorkspaceActionInput(
                                            environmentId = env.id,
                                            actionId = "act_permit",
                                            environmentVersion = env.version,
                                            requestId = "req_permit_d"
                                        ),
                                        environment = env,
                                        permissions = permissions,
                                        permit = forgedPermit
                                    )
                                }
                                assertEquals(WorkspaceOperationState.FAILED, actionResult.state, "case $case: forged permit action must be FAILED, got ${actionResult.state}")
                                assertEquals(WorkspaceErrorCode.WORKSPACE_BUSY, actionResult.error?.code, "case $case: error must be WORKSPACE_BUSY")
                                assertFalse(marker.exists(), "case $case: marker must NOT exist (forged permit did not grant access)")
                            }
                        }
                    }
                }
            }.onFailure { e ->
                fail("case $case failed: ${e.message}")
            }
        }
    }

    /**
     * S6: T05 归档前像（archive preimage）必须完整覆盖 ignored / symlink / mode，且恢复侧对
     * 指纹计算失败保持 fail-closed。
     *
     * 复现路径：真实 Git + MANAGED_WORKTREE + prepareWorkspace；用 [InMemoryWorkspaceStore] 装饰器
     * 在 archive 快照 RUNNING+sourceSnapshotId 落盘后抛 IOException 模拟崩溃（快照已写、remove 未发生）。
     * 崩溃后对 checkout 施加某一类"快照后改动"，重开 scanAndRecoverPendingOperations：
     * 因指纹现覆盖 ignored/symlink/mode，任何改动都使 currentFingerprint != snapshotFingerprint
     * （或 computeFingerprint 直接失败）→ RECOVERY_REQUIRED，绝不放行 worktree remove --force。
     *
     * 场景：
     *   a. 修改 ignored 文件内容
     *   b. 新建 ignored 文件
     *   c. 改 symlink 指向
     *   d. 改可执行位（chmod -x）
     *   e. 注入 computeFingerprint 失败（读失败 = 状态未知，不放行）
     */
    @Test
    fun archivePreimageComparisonIncludesIgnoredLinksModesAndReadFailures() {
        // a: 修改 ignored 文件内容
        archivePreimageCase(
            caseName = "a_modify_ignored",
            mutate = { checkout -> File(checkout, "ignored.log").writeText("external-newer-value") },
            assertMutated = { checkout ->
                assertEquals("external-newer-value", File(checkout, "ignored.log").readText())
            }
        )

        // b: 新建 ignored 文件（快照时不存在）
        archivePreimageCase(
            caseName = "b_new_ignored",
            mutate = { checkout -> File(checkout, "new.log").writeText("brand-new") },
            assertMutated = { checkout ->
                assertEquals("brand-new", File(checkout, "new.log").readText())
            }
        )

        // c: 改 symlink 指向（指向不存在的 target2.txt —— 唯一差异即链接目标文本，
        //    隔离校验"symlink 目标进指纹"这一特性，而非被引用文件的存在性）
        archivePreimageCase(
            caseName = "c_symlink_target",
            mutate = { checkout ->
                Files.deleteIfExists(checkout.toPath().resolve("link.txt"))
                Files.createSymbolicLink(checkout.toPath().resolve("link.txt"), Paths.get("target2.txt"))
            },
            assertMutated = { checkout ->
                val link = checkout.toPath().resolve("link.txt")
                assertTrue(Files.isSymbolicLink(link), "case c: link.txt must remain a symlink")
                assertEquals("target2.txt", Files.readSymbolicLink(link).toString())
            }
        )

        // Only POSIX filesystems expose an executable bit that the test can change independently.
        if ("posix" in java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()) {
            archivePreimageCase(
                caseName = "d_exec_mode",
                mutate = { checkout -> assertTrue(File(checkout, "exec.sh").setExecutable(false, false)) },
                assertMutated = { checkout ->
                    val exec = File(checkout, "exec.sh")
                    assertTrue(exec.exists(), "case d: exec.sh must be preserved")
                    assertFalse(exec.canExecute(), "case d: exec.sh must remain non-executable")
                }
            )
        }

        // e: 注入 computeFingerprint 失败 —— 恢复侧必须 fail-closed，不放行删除
        archivePreimageCase(
            caseName = "e_fingerprint_failure",
            throwOnFingerprint = true,
            mutate = { _ -> },
            assertMutated = { checkout ->
                assertTrue(checkout.exists() && checkout.isDirectory, "case e: checkout must be preserved")
            }
        )
    }

    /**
     * S6 辅助：搭建源仓库（含 .gitignore / symlink / 可执行文件），创建并准备 MANAGED_WORKTREE，
     * 在 worktree 内放置 ignored/symlink/mode fixture，模拟"快照后崩溃"，施加快照后改动，重开恢复，
     * 断言工作区落入 RECOVERY_REQUIRED 且 checkout 与改动被完整保留。
     */
    private fun archivePreimageCase(
        caseName: String,
        mutate: (File) -> Unit,
        assertMutated: (File) -> Unit,
        throwOnFingerprint: Boolean = false
    ) {
        runCatching {
            runBlocking {
                val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                    .first { File(it, "settings.gradle.kts").isFile }
                val parent = File(repo, "temp/worktree-invariant/fixtures").apply { mkdirs() }
                val root = Files.createTempDirectory(parent.toPath(), "preimage-").toFile().canonicalFile
                try {
                    // 源仓库：base + .gitignore(*.log) + commit
                    val source = File(root, "source").apply { mkdirs() }
                    git(source, "init")
                    git(source, "checkout", "-b", "main")
                    git(source, "config", "core.filemode", "true")
                    File(source, "base").writeText("base")
                    File(source, ".gitignore").writeText("*.log\n")
                    git(source, "add", ".")
                    git(source, "commit", "-m", "init")

                    val wt = File(root, "wt").absolutePath
                    val snaps = File(root, "wt-snap").absolutePath

                    // 装饰器：archive 快照 RUNNING+sourceSnapshotId 落盘后抛 IOException 模拟崩溃
                    val delegate = InMemoryWorkspaceStore()
                    val store = object : WorkspaceStore by delegate {
                        var armed = false
                        override suspend fun saveOperation(operation: WorkspaceOperation) {
                            delegate.saveOperation(operation)
                            if (armed && operation.kind == WorkspaceOperationKind.ARCHIVE &&
                                operation.state == WorkspaceOperationState.RUNNING &&
                                operation.sourceSnapshotId != null
                            ) {
                                armed = false
                                throw java.io.IOException("simulate crash after snapshot journal")
                            }
                        }
                    }

                    val manager = WorkspaceManager(
                        store, InMemorySessionStore(), InMemoryHistoryStore(),
                        worktreesRoot = wt, snapshotsRoot = snaps
                    )
                    val requested = manager.createWorkspace(
                        "p", source.absolutePath,
                        selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE)
                    )
                    manager.prepareWorkspace(requested.id, source.absolutePath, "prepare_$caseName")
                    val ws = delegate.getWorkspace(requested.id)!!
                    val checkout = File(ws.checkoutRoot)

                    // worktree fixture（untracked/ignored，快照时存在）：ignored 文件 / symlink / 可执行文件
                    File(checkout, "ignored.log").writeText("snapshot-value")
                    File(checkout, "target.txt").writeText("original target content")
                    Files.createSymbolicLink(checkout.toPath().resolve("link.txt"), Paths.get("target.txt"))
                    val exec = File(checkout, "exec.sh").apply { writeText("#!/bin/sh\necho exec\n") }
                    exec.setExecutable(true, false)

                    store.armed = true
                    assertFailsWith<java.io.IOException> {
                        manager.archiveWorkspace(ws.id, ArchiveWorkspaceInput(requestId = "archive_$caseName"))
                    }

                    // 快照后改动
                    mutate(checkout)

                    // 重开恢复：e 场景注入 computeFingerprint 失败
                    val restartedSnapshotService = if (throwOnFingerprint) {
                        object : WorkspaceSnapshotService(File(snaps)) {
                            override suspend fun computeFingerprint(checkoutRoot: String): String =
                                throw java.io.IOException("injected computeFingerprint failure")
                        }
                    } else {
                        WorkspaceSnapshotService(File(snaps))
                    }
                    val restarted = WorkspaceManager(
                        delegate, InMemorySessionStore(), InMemoryHistoryStore(),
                        worktreesRoot = wt, snapshotsRoot = snaps,
                        snapshotService = restartedSnapshotService
                    )
                    restarted.scanAndRecoverPendingOperations("p")

                    val wsAfter = delegate.getWorkspace(ws.id)!!
                    assertNotEquals(WorkspaceState.ARCHIVED, wsAfter.state, "case $caseName: must NOT be ARCHIVED")
                    assertEquals(
                        WorkspaceState.RECOVERY_REQUIRED, wsAfter.state,
                        "case $caseName: must be RECOVERY_REQUIRED, got ${wsAfter.state}"
                    )
                    assertTrue(
                        checkout.exists() && checkout.isDirectory,
                        "case $caseName: checkout must be preserved (not force-removed)"
                    )
                    assertMutated(checkout)
                } finally {
                    root.deleteRecursively()
                }
            }
        }.onFailure { e ->
            fail("case $caseName failed: ${e.message}")
        }
    }

    /**
     * S7: T07 统一安全落盘原语必须安全处理全部文件类型互换（FILE/SYMLINK/DIRECTORY）：
     * rollback 的 FILE 分支若目标本身是 symlink，必须先删链接本身再写文件，
     * 绝不 writeBytes 写穿到链接目标（工作区外文件）；且回滚只动 journal 路径，不碰 journal 外路径。
     *
     * 直接用 WorkspaceSnapshotService（captureSnapshot + rollbackCheckout）验证原语
     * （原语与 rollbackCheckout 第二遍共用，第三轮复现场景即场景 a）。
     * 场景：
     *   a. FILE→SYMLINK 回滚：preimage FILE，current symlink → 删链接写文件，外部目标不变（第三轮复现）
     *   b. SYMLINK→FILE 回滚：preimage SYMLINK，current FILE → 删文件建链接
     *   c. DIR→FILE 回滚：preimage DIRECTORY，current FILE → 删文件建目录
     *   d. FILE→DIR 回滚：preimage FILE，current DIRECTORY → 删目录写文件
     *   e. 只动 journal 路径：journal 外路径（外部新增目录/文件）不被触碰
     */
    @Test
    fun safeMaterializationHandlesAllFileTypeTransitions() {
        val cases = listOf(
            "a_file_to_symlink",
            "b_symlink_to_file",
            "c_dir_to_file",
            "d_file_to_dir",
            "e_journal_paths_only"
        )
        for (case in cases) {
            runCatching {
                runBlocking {
                    val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                        .first { File(it, "settings.gradle.kts").isFile }
                    val parent = File(repo, "temp/worktree-invariant/fixtures").apply { mkdirs() }
                    val root = Files.createTempDirectory(parent.toPath(), "safe-mat-$case-").toFile().canonicalFile
                    try {
                        val dir = File(root, "repo").apply { mkdirs() }
                        git(dir, "init")
                        File(dir, "base.txt").writeText("base")
                        when (case) {
                            "b_symlink_to_file" -> {
                                File(dir, "t.txt").writeText("target content")
                                Files.createSymbolicLink(dir.toPath().resolve("f.txt"), Paths.get("t.txt"))
                            }
                            "c_dir_to_file" -> {
                                File(dir, "d").mkdirs() // 空目录：PREIMAGE 捕获记录为 DIRECTORY 条目
                            }
                            else -> File(dir, "f.txt").writeText("original-file")
                        }
                        git(dir, "add", ".")
                        git(dir, "commit", "-m", "init")

                        val service = WorkspaceSnapshotService(File(root, "snapshots"))
                        val snapshot = service.captureSnapshot(dir.path, "ws", "safe-mat", SnapshotCaptureType.PREIMAGE)
                        val f = File(dir, "f.txt")
                        val external = File(root, "external-$case.txt").apply { writeText("external-original-$case") }

                        when (case) {
                            "a_file_to_symlink" -> {
                                // preimage FILE；current symlink（postimage 匹配，hash = 链接目标文本 SHA-256）
                                f.delete()
                                Files.createSymbolicLink(f.toPath(), Paths.get("external-$case.txt"))
                                val journal = safeMatJournal(
                                    "f.txt", true,
                                    postExists = true, postType = "symlink", postHash = safeMatSha256("external-$case.txt")
                                )
                                service.rollbackCheckout(dir.path, snapshot.snapshotId, journal)
                                assertTrue(f.isFile, "case $case: f.txt must be restored to a real file")
                                assertEquals("original-file", f.readText(), "case $case: f.txt content must come from the preimage blob")
                                assertEquals(
                                    "external-original-$case", external.readText(),
                                    "case $case: rollback must replace the link itself, never write through a matching postimage symlink"
                                )
                            }

                            "b_symlink_to_file" -> {
                                // preimage SYMLINK f.txt->t.txt；current FILE
                                Files.deleteIfExists(f.toPath())
                                f.writeText("mutated-file")
                                val journal = safeMatJournal(
                                    "f.txt", true,
                                    postExists = true, postType = "file", postHash = safeMatSha256("mutated-file")
                                )
                                service.rollbackCheckout(dir.path, snapshot.snapshotId, journal)
                                assertTrue(Files.isSymbolicLink(f.toPath()), "case $case: f.txt must be restored to a symlink")
                                assertEquals(
                                    "t.txt", Files.readSymbolicLink(f.toPath()).toString(),
                                    "case $case: symlink target must come from the preimage"
                                )
                            }

                            "c_dir_to_file" -> {
                                // preimage DIRECTORY（空目录 d）；current FILE
                                val d = File(dir, "d")
                                d.deleteRecursively()
                                d.writeText("mutated-dir-as-file")
                                val journal = safeMatJournal(
                                    "d", false,
                                    postExists = true, postType = "file", postHash = safeMatSha256("mutated-dir-as-file")
                                )
                                service.rollbackCheckout(dir.path, snapshot.snapshotId, journal)
                                assertTrue(d.isDirectory, "case $case: d must be restored to a directory")
                            }

                            "d_file_to_dir" -> {
                                // preimage FILE f.txt；current DIRECTORY（内含操作产物）
                                f.delete()
                                f.mkdirs()
                                File(f, "inner.txt").writeText("inner")
                                val journal = listOf(WorkspacePhaseRecord(
                                    WorkspacePhase.MATERIALIZE_TARGET, "now",
                                    affectedPaths = listOf(
                                        PhasePathRecord("f.txt", true, postExists = true, postType = "directory"),
                                        PhasePathRecord("f.txt/inner.txt", false, postExists = true,
                                            postType = "file", postHash = safeMatSha256("inner"))
                                    )
                                ))
                                service.rollbackCheckout(dir.path, snapshot.snapshotId, journal)
                                assertTrue(f.isFile, "case $case: f.txt must be restored to a file")
                                assertEquals("original-file", f.readText(), "case $case: f.txt content must come from the preimage blob")
                            }

                            "e_journal_paths_only" -> {
                                // 操作删除 f.txt（preimage 存在）→ 回滚恢复 preimage 内容；
                                // 操作创建 created/（preimage 不存在）→ 回滚删除该路径；
                                // journal 外的外部新增 outer/ 目录与文件 → 不得触碰。
                                val created = File(dir, "created").apply { mkdirs() }
                                File(created, "op-file.txt").writeText("op")
                                File(dir, "outer").mkdirs()
                                File(dir, "outer/keep.txt").writeText("keep")
                                f.delete()
                                val journal = listOf(
                                    WorkspacePhaseRecord(
                                        WorkspacePhase.MATERIALIZE_TARGET, "now",
                                        affectedPaths = listOf(
                                            PhasePathRecord("f.txt", true, postExists = false),
                                            PhasePathRecord("created", false, postExists = true, postType = "directory"),
                                            PhasePathRecord("created/op-file.txt", false, postExists = true,
                                                postType = "file", postHash = safeMatSha256("op"))
                                        )
                                    )
                                )
                                service.rollbackCheckout(dir.path, snapshot.snapshotId, journal)
                                assertTrue(f.isFile, "case $case: f.txt must be restored from preimage")
                                assertEquals("original-file", f.readText(), "case $case: f.txt content must come from the preimage blob")
                                assertFalse(File(dir, "created").exists(), "case $case: operation-created path must be removed by rollback")
                                assertEquals("keep", File(dir, "outer/keep.txt").readText(), "case $case: rollback must NOT touch non-journal paths")
                            }
                        }
                    } finally {
                        root.deleteRecursively()
                    }
                }
            }.onFailure { e ->
                fail("case $case failed: ${e.message}")
            }
        }
    }

    private fun safeMatSha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun safeMatJournal(
        path: String,
        preExists: Boolean,
        postExists: Boolean,
        postType: String?,
        postHash: String?
    ): List<WorkspacePhaseRecord> = listOf(
        WorkspacePhaseRecord(
            WorkspacePhase.MATERIALIZE_TARGET, "now",
            affectedPaths = listOf(
                PhasePathRecord(path, preExists, postExists = postExists, postType = postType, postHash = postHash)
            )
        )
    )

    /**
     * T06: S8 写前意图日志与突变边界恢复
     *
     * 验证 handoff 的 MATERIALIZE_TARGET 崩溃边界：若 saveOperation 在物化后崩溃
     * （journal 缺少 MATERIALIZE_TARGET 记录），恢复侧必须检测 journal 不完整
     * 并标记 RECOVERY_REQUIRED，绝不将含残留文件的 worktree 标记为 READY。
     *
     * 场景：
     *   a. MATERIALIZE_TARGET 崩溃 → RECOVERY_REQUIRED，feature.txt 仍存
     *   b. 重开恢复（新 WorkspaceManager 同 store）→ 同样 RECOVERY_REQUIRED
     *   c. materialize+verify 完成、clean_source 未持久 → 安全回滚（不半 READY）
     */
    @Test
    fun everyMutationBoundaryRecoversWithSqliteReopen() {
        val cases = listOf(
            "a_materialize_crash_recovery_required",
            "b_reopen_recovery_required",
            "c_verify_done_clean_source_rollback"
        )
        for (case in cases) {
            runCatching {
                mutationBoundaryCase(case)
            }.onFailure { e ->
                fail("case $case failed: ${e.message}")
            }
        }
    }

    private fun mutationBoundaryCase(caseName: String) {
        runBlocking {
            val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }
            val parent = File(repo, "temp/worktree-invariant/fixtures").apply { mkdirs() }
            val root = Files.createTempDirectory(parent.toPath(), "mut-boundary-").toFile().canonicalFile
            try {
                // 源仓库：base + feature.txt (untracked) + commit base
                val source = File(root, "source").apply { mkdirs() }
                git(source, "init")
                git(source, "checkout", "-b", "main")
                File(source, "base").writeText("base")
                git(source, "add", ".")
                git(source, "commit", "-m", "init")

                val wt = File(root, "wt").path

                // 真实磁盘 SQLite 数据库（第四轮复审 P1 修复: 杜绝内存 store 冒充重开）
                val dbFile = File(root, "data.db")
                var driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
                MederiDataDatabase.Schema.create(driver)
                Mederi.migrateDataDb(driver)
                val dbm = xyz.mederi.store.db.DatabaseManager(driver)
                var sessions: xyz.mederi.store.SessionStore = SqliteSessionStore(dbm)
                var delegate: WorkspaceStore = SqliteWorkspaceStore(dbm)

                // 装饰器：根据 case 在特定阶段注入崩溃
                val store = object : WorkspaceStore by delegate {
                    var armed = false
                    override suspend fun saveOperation(operation: WorkspaceOperation) {
                        if (armed) {
                            when (caseName) {
                                // 场景a/b: 当 saveOperation 包含 MATERIALIZE_TARGET phaseRecord 时崩溃
                                "a_materialize_crash_recovery_required",
                                "b_reopen_recovery_required" -> {
                                    if (operation.kind == WorkspaceOperationKind.HANDOFF &&
                                        operation.phaseRecords.any { it.phase == WorkspacePhase.MATERIALIZE_TARGET }
                                    ) {
                                        armed = false
                                        throw java.io.IOException("crash: $caseName - after materialize, before MATERIALIZE_TARGET journal")
                                    }
                                }
                                // 场景c: 当 saveOperation 包含 CLEAN_SOURCE phaseRecord 时崩溃
                                "c_verify_done_clean_source_rollback" -> {
                                    if (operation.kind == WorkspaceOperationKind.HANDOFF &&
                                        operation.phaseRecords.any { it.phase == WorkspacePhase.CLEAN_SOURCE }
                                    ) {
                                        armed = false
                                        throw java.io.IOException("crash: $caseName - after verify, before CLEAN_SOURCE journal")
                                    }
                                }
                            }
                        }
                        delegate.saveOperation(operation)
                    }
                }

                val now = java.time.Instant.now().toString()
                sessions.insert(
                    xyz.mederi.domain.model.Session(
                        id = "session", projectId = "p", title = "MutBoundary",
                        status = xyz.mederi.domain.model.SessionStatus.IDLE,
                        agentMode = xyz.mederi.domain.model.AgentMode.AUTONOMOUS,
                        aiModel = null, reasoningLevel = null,
                        env = emptyMap(), createdAt = now, updatedAt = now
                    )
                )

                // 创建源工作区 (LOCAL)
                managerCreateAndPrepareLocal(store, sessions, source.path, wt)

                // 在源工作区放置 untracked 文件
                File(source, "feature.txt").writeText("carried-untracked-content")

                // 预览 handoff
                val manager = WorkspaceManager(store, sessions, InMemoryHistoryStore(), worktreesRoot = wt)
                val preview = manager.previewHandoff("session", HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
                val targetId = preview.target.workspace.id

                // 注入崩溃
                store.armed = true
                assertFailsWith<java.io.IOException> {
                    manager.executeHandoff("session", HandoffInput(
                        targetKind = WorkspaceKind.MANAGED_WORKTREE,
                        previewToken = preview.token,
                        requestId = "handoff_mutation_boundary"
                    ))
                }

                // 验证物化已发生（feature.txt 已写到 target）
                val targetWs = delegate.getWorkspace(targetId)!!
                assertTrue(
                    File(targetWs.checkoutRoot, "feature.txt").exists(),
                    "case $caseName: materialization must have occurred (feature.txt present in target)"
                )

                // 真实关闭旧连接并重开磁盘 SQLite（验证跨进程重启/重开后的真实恢复能力）
                driver.close()
                val reopenedDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
                val reopenedDbm = xyz.mederi.store.db.DatabaseManager(reopenedDriver)
                val reopenedSessions = SqliteSessionStore(reopenedDbm)
                val reopenedStore = SqliteWorkspaceStore(reopenedDbm)
                val restarted = WorkspaceManager(
                    reopenedStore, reopenedSessions, InMemoryHistoryStore(),
                    worktreesRoot = wt
                )
                restarted.scanAndRecoverPendingOperations("p")

                val afterOp = delegate.listOperations("p")
                    .find { it.kind == WorkspaceOperationKind.HANDOFF }
                    ?: fail("case $caseName: handoff operation must exist after recovery")
                val tgtAfter = delegate.getWorkspace(targetId)!!

                when (caseName) {
                    "a_materialize_crash_recovery_required" -> {
                        // journal 不完整 (SWITCH done, MATERIALIZE not persisted) → RECOVERY_REQUIRED
                        assertEquals(
                            WorkspaceOperationState.RECOVERY_REQUIRED, afterOp.state,
                            "case a: incomplete journal (SWITCH done, MATERIALIZE missing) must yield RECOVERY_REQUIRED, got ${afterOp.state}"
                        )
                        assertEquals(
                            WorkspaceState.RECOVERY_REQUIRED, tgtAfter.state,
                            "case a: target must be RECOVERY_REQUIRED, not READY (residual files present)"
                        )
                        // feature.txt 仍存在（未被错误清空）
                        assertTrue(
                            File(tgtAfter.checkoutRoot, "feature.txt").exists(),
                            "case a: feature.txt must still exist (rollback was NOT safe)"
                        )
                    }

                    "b_reopen_recovery_required" -> {
                        // 模拟"重开"：新 WorkspaceManager 同 store，恢复逻辑同上
                        assertEquals(
                            WorkspaceOperationState.RECOVERY_REQUIRED, afterOp.state,
                            "case b: reopened recovery must detect incomplete journal → RECOVERY_REQUIRED, got ${afterOp.state}"
                        )
                        assertEquals(
                            WorkspaceState.RECOVERY_REQUIRED, tgtAfter.state,
                            "case b: target must be RECOVERY_REQUIRED after reopen"
                        )
                    }

                    "c_verify_done_clean_source_rollback" -> {
                        // materialize + verify 已完成（有 MATERIALIZE_TARGET 记录），clean_source 未持久
                        // 恢复侧检测 targetMatchesAccepted && sourceClean → 补齐绑定 → SUCCEEDED
                        // 关键约束：不半 READY（若一方 READY 另一方必须也 READY）
                        val srcWs = delegate.listWorkspaces("p").find { it.kind == WorkspaceKind.LOCAL }
                        assertNotNull(srcWs, "case c: source workspace must exist")
                        // 不允许半 READY：target READY 但 source 未 READY
                        if (tgtAfter.state == WorkspaceState.READY) {
                            assertEquals(
                                WorkspaceState.READY, srcWs.state,
                                "case c: if target is READY, source must also be READY (no half-READY)"
                            )
                        }
                        if (srcWs.state == WorkspaceState.READY) {
                            assertEquals(
                                WorkspaceState.READY, tgtAfter.state,
                                "case c: if source is READY, target must also be READY (no half-READY)"
                            )
                        }
                        // 恢复要么安全完成（SUCCEEDED），要么回滚（FAILED/RECOVERY_REQUIRED），绝不半 READY
                        assertTrue(
                            afterOp.state == WorkspaceOperationState.SUCCEEDED ||
                                afterOp.state == WorkspaceOperationState.FAILED ||
                                afterOp.state == WorkspaceOperationState.RECOVERY_REQUIRED,
                            "case c: op must be SUCCEEDED/FAILED/RECOVERY_REQUIRED, got ${afterOp.state}"
                        )
                    }
                }

                println("[T06 mutation-boundary] case=$caseName op.state=${afterOp.state} target.state=${tgtAfter.state} featureExists=${File(tgtAfter.checkoutRoot, "feature.txt").exists()}")
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private suspend fun managerCreateAndPrepareLocal(
        store: WorkspaceStore, sessions: xyz.mederi.store.SessionStore, sourcePath: String, wt: String
    ) {
        val manager = WorkspaceManager(store, sessions, InMemoryHistoryStore(), worktreesRoot = wt)
        val ws = manager.createWorkspace("p", sourcePath, selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL), sessionId = "session")
        // LOCAL workspace is immediately READY, no prepare needed
        val wsReady = store.getWorkspace(ws.id)!!
        assertEquals(WorkspaceState.READY, wsReady.state)
    }

    /**
     * T02：SUCCEEDED 历史 handoff 不得在启动恢复时被重放。
     *
     * 真实 Git + InMemoryStore + WorkspaceManager。Local→A→Local 多次往返后，最新提交
     * （commitBinding CAS 原子完成）的会话绑定指向 Local；再次 scanAndRecoverPendingOperations
     * 时不得被较早的 Local→A SUCCEEDED handoff 覆盖回 A。
     */
    @Test
    fun latestCommittedBindingSurvivesRepeatedHandoffsAndStartup() {
        runBlocking {
            val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }
            val parent = File(repo, "temp/worktree-invariant/fixtures").apply { mkdirs() }
            val root = Files.createTempDirectory(parent.toPath(), "binding-survive-").toFile().canonicalFile
            try {
                // 源仓库：base + commit
                val source = File(root, "source").apply { mkdirs() }
                git(source, "init")
                git(source, "checkout", "-b", "main")
                File(source, "base").writeText("base")
                git(source, "add", ".")
                git(source, "commit", "-m", "init")

                val wt = File(root, "wt").path
                val localId = "ws_local_p"

                val sessions = InMemorySessionStore()
                val store = InMemoryWorkspaceStore(sessions)

                val now = java.time.Instant.now().toString()
                sessions.insert(
                    xyz.mederi.domain.model.Session(
                        id = "session", projectId = "p", title = "BindingSurvive",
                        status = xyz.mederi.domain.model.SessionStatus.IDLE,
                        agentMode = xyz.mederi.domain.model.AgentMode.AUTONOMOUS,
                        aiModel = null, reasoningLevel = null,
                        env = emptyMap(), createdAt = now, updatedAt = now
                    )
                )

                val manager = WorkspaceManager(store, sessions, InMemoryHistoryStore(), worktreesRoot = wt)
                managerCreateAndPrepareLocal(store, sessions, source.path, wt)
                assertEquals(localId, sessions.get("session")!!.workspaceId, "session must start bound to local")

                var seq = 0
                suspend fun handoff(targetKind: WorkspaceKind) {
                    seq += 1
                    val preview = manager.previewHandoff("session", HandoffPreviewInput(targetKind = targetKind))
                    manager.executeHandoff(
                        "session",
                        HandoffInput(
                            targetKind = targetKind,
                            previewToken = preview.token,
                            requestId = "bind_survive_${seq}"
                        )
                    )
                }

                // 第一轮往返：Local→A→Local
                handoff(WorkspaceKind.MANAGED_WORKTREE)
                val afterToA = sessions.get("session")!!.workspaceId
                assertNotEquals(localId, afterToA, "after Local→A the session must point to the worktree, not local")
                delay(10)
                handoff(WorkspaceKind.LOCAL)
                assertEquals(localId, sessions.get("session")!!.workspaceId, "after A→Local the session must point back to local")
                delay(10)

                // 启动扫描：不得被较早的 Local→A SUCCEEDED handoff 覆盖回 A
                WorkspaceManager(store, sessions, InMemoryHistoryStore(), worktreesRoot = wt)
                    .scanAndRecoverPendingOperations("p")
                assertEquals(
                    localId, sessions.get("session")!!.workspaceId,
                    "startup scan must not replay the older Local→A handoff over the latest local binding"
                )

                // 再多轮往返：Local→A→Local→A→Local，每轮保持最新绑定
                handoff(WorkspaceKind.MANAGED_WORKTREE)
                delay(10)
                handoff(WorkspaceKind.LOCAL)
                assertEquals(localId, sessions.get("session")!!.workspaceId, "round 2 A→Local must land on local")
                delay(10)
                handoff(WorkspaceKind.MANAGED_WORKTREE)
                delay(10)
                handoff(WorkspaceKind.LOCAL)
                assertEquals(localId, sessions.get("session")!!.workspaceId, "round 3 A→Local must land on local")
                delay(10)

                // 再次启动扫描（多次 SUCCEEDED 历史并存）：最新绑定仍为 local
                WorkspaceManager(store, sessions, InMemoryHistoryStore(), worktreesRoot = wt)
                    .scanAndRecoverPendingOperations("p")
                assertEquals(
                    localId, sessions.get("session")!!.workspaceId,
                    "multi-round startup scan must keep the latest committed binding (local)"
                )

                println("[T02 binding-survive] latest committed binding preserved across repeated handoffs + startup scans")
            } finally {
                root.deleteRecursively()
            }
        }
    }

    /**
     * T14: RESTORE 恢复核验必须覆盖 index（stage 语义指纹）、symlink 目标、墓碑（exists=false）、
     * 可执行位（unixMode）与仓库身份（HEAD/headRef），而非仅逐 FILE hash。
     *
     * 复现路径：真实 git 源仓库 + `git worktree add` 得到的 MANAGED_WORKTREE；在其中制造
     * staged 变更（使 index 与 HEAD 分离）、墓碑（`git rm` 已提交的 tracked 文件）、symlink、
     * 可执行文件，用 WorkspaceSnapshotService.captureSnapshot 捕获快照，先断言"未破坏=完整"，
     * 再按 case 破坏单一维度并断言 verifyRestoreCompleteness 的结果（参数化 helper，失败输出 case 名）。
     */
    @Test
    fun restoreCompletenessIncludesIndexTombstonesLinksModesAndIdentity() {
        // a. index 未恢复（reset 回 HEAD，stage 指纹变化）→ 即便文件全对也不得 READY
        restoreCompletenessCase("a_index_not_restored", expected = false) { target ->
            git(target, "reset")
        }
        // b. symlink 目标被改写 → 指纹不符
        restoreCompletenessCase("b_symlink_target_mismatch", expected = false) { target ->
            val link = target.toPath().resolve("link.txt")
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, Paths.get("other.txt"))
        }
        // c. 墓碑：manifest 声明 tomb.txt 不存在，但恢复后该文件复活 → 必须判不完整
        restoreCompletenessCase("c_tombstone_revived", expected = false) { target ->
            File(target, "tomb.txt").writeText("should not exist")
        }
        // d. 可执行位被清除 → mode 不符
        if ("posix" in java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()) {
            restoreCompletenessCase("d_exec_mode_cleared", expected = false) { target ->
                assertTrue(File(target, "exec.sh").setExecutable(false, false))
            }
        }
        // e. 完整恢复：HEAD/headRef + index + 全部条目（file/symlink/tombstone/mode）+ worktree 注册 均匹配
        restoreCompletenessCase("e_full_restore", expected = true) { _ -> }
    }

    private fun restoreCompletenessCase(
        caseName: String,
        expected: Boolean,
        breakState: (File) -> Unit
    ) {
        runCatching {
            runBlocking {
                val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                    .first { File(it, "settings.gradle.kts").isFile }
                val parent = File(repo, "temp/worktree-invariant/fixtures").apply { mkdirs() }
                val root = Files.createTempDirectory(parent.toPath(), "restore-").toFile().canonicalFile
                try {
                    // 源仓库：base 提交（含将被 `git rm` 成墓碑的 tomb.txt）
                    val source = File(root, "source").apply { mkdirs() }
                    git(source, "init")
                    git(source, "checkout", "-b", "main")
                    git(source, "config", "core.filemode", "true")
                    File(source, "tracked.txt").writeText("tracked content")
                    File(source, "tomb.txt").writeText("tomb content")
                    git(source, "add", ".")
                    git(source, "commit", "-m", "init")

                    // MANAGED_WORKTREE（新分支 task）
                    val target = File(root, "wt").canonicalFile
                    git(source, "worktree", "add", target.absolutePath, "-b", "task")

                    // 使 index 与 HEAD 分离：staged 新增文件 + 墓碑（git rm）
                    File(target, "staged.txt").writeText("staged content")
                    git(target, "add", "staged.txt")
                    git(target, "rm", "tomb.txt")
                    // symlink + 可执行文件（untracked 但存在于工作区）
                    File(target, "target.txt").writeText("link target content")
                    Files.createSymbolicLink(target.toPath().resolve("link.txt"), Paths.get("target.txt"))
                    val exec = File(target, "exec.sh").apply { writeText("#!/bin/sh\necho exec\n") }
                    exec.setExecutable(true, false)

                    val snaps = File(root, "snaps").absolutePath
                    val snapService = WorkspaceSnapshotService(File(snaps))
                    val manifest = snapService.captureSnapshot(target.absolutePath, "ws_restore", "restore-test")
                    assertEquals(
                        "refs/heads/task", manifest.headRef,
                        "case $caseName: setup must record the worktree branch ref"
                    )
                    assertNotNull(
                        manifest.indexStageSha256,
                        "case $caseName: setup must record an index stage fingerprint"
                    )

                    val manager = WorkspaceManager(
                        InMemoryWorkspaceStore(), InMemorySessionStore(), InMemoryHistoryStore(),
                        worktreesRoot = File(root, "wtroot").absolutePath, snapshotsRoot = snaps
                    )

                    // 未破坏状态必须判定为完整（基线）
                    assertTrue(
                        manager.verifyRestoreCompleteness(target, manifest.snapshotId),
                        "case $caseName: unbroken restore must be verified complete"
                    )

                    breakState(target)
                    val result = manager.verifyRestoreCompleteness(target, manifest.snapshotId)
                    assertEquals(expected, result, "case $caseName: verifyRestoreCompleteness expected $expected")
                } finally {
                    root.deleteRecursively()
                }
            }
        }.onFailure { e ->
            fail("case $caseName failed: ${e.message}")
        }
    }

    /**
     * 测试用故障注入驱动：对命中 [failOnSql] 的写语句抛异常，强制 commitBinding 的
     * transactionWithResult 回滚，以验证"Session/Workspace/operation 全提交或全不提交"的原子性。
     */
    private class FaultInjectingDriver(
        private val delegate: SqlDriver,
        private val failOnSql: (String) -> Boolean
    ) : SqlDriver {
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?
        ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?
        ): QueryResult<Long> {
            if (failOnSql(sql)) throw IllegalStateException("injected write failure: $sql")
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun newTransaction(): QueryResult<Transacter.Transaction> = delegate.newTransaction()
        override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()
        override fun addListener(vararg queryKeys: String, listener: Query.Listener) =
            delegate.addListener(*queryKeys, listener = listener)
        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) =
            delegate.removeListener(*queryKeys, listener = listener)
        override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(*queryKeys)
        override fun close() = delegate.close()
    }

    /**
     * T14: commitBinding 在任一写入边界失败时，Session/Workspace/operation 必须整体回滚（无半写入）；
     * 成功时三者全部落盘；重复 requestId（同一 operation id/idempotencyKey）重试不产生重复副作用。
     * 用真实 SqliteWorkspaceStore（内存 SQLite）+ 故障注入驱动在 commitBinding 各写入边界抛错验证。
     */
    @Test
    fun bindingTransactionFailureLeavesAllRecordsUnchanged() {
        // 各写入边界抛错 → 事务整体回滚
        bindingBoundaryCase("session_write") { sql -> sql.contains("UPDATE sessions SET workspace_id") }
        bindingBoundaryCase("workspace_write") { sql -> sql.contains("INTO workspaces (") }
        bindingBoundaryCase("operation_write") { sql -> sql.contains("INTO workspace_operations (") }
        // 成功落盘 + 重复 requestId 无重复副作用
        bindingSuccessAndIdempotency()
    }

    private fun bindingBoundaryCase(boundary: String, failOnSql: (String) -> Boolean) {
        runCatching {
            runBlocking {
                val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                MederiDataDatabase.Schema.create(driver)
                Mederi.migrateDataDb(driver)

                val faultDriver = FaultInjectingDriver(driver, failOnSql)
                val faultDbm = xyz.mederi.store.db.DatabaseManager(faultDriver)
                val sessionStore = SqliteSessionStore(faultDbm)
                val workspaceStore = SqliteWorkspaceStore(faultDbm)

                val sessionId = "sess_$boundary"
                sessionStore.insert(
                    Session(
                        id = sessionId, projectId = "proj_1", title = "Boundary $boundary",
                        status = SessionStatus.IDLE, agentMode = AgentMode.AUTONOMOUS,
                        aiModel = null, reasoningLevel = null,
                        createdAt = "2026-11-04T00:00:00Z", updatedAt = "2026-11-04T00:00:00Z"
                    )
                )

                val wsId = "ws_target_$boundary"
                val opId = "op_target_$boundary"
                val newWorkspace = Workspace(
                    id = wsId, projectId = "proj_1", kind = WorkspaceKind.MANAGED_WORKTREE,
                    checkoutRoot = "/worktrees/$wsId/checkout", projectRelativePath = "",
                    state = WorkspaceState.READY, generation = 1L,
                    createdAt = "2026-11-04T00:00:00Z", lastUsedAt = "2026-11-04T00:00:00Z"
                )
                val plan = WorkspaceOperationPlan.CreatePlan(
                    requestHash = "req_$boundary",
                    selection = WorkspaceSelection(WorkspaceKind.MANAGED_WORKTREE),
                    targetPath = "/worktrees/$wsId/checkout"
                )
                val operation = WorkspaceOperation(
                    id = opId, projectId = "proj_1", sessionId = sessionId, workspaceId = wsId,
                    kind = WorkspaceOperationKind.CREATE, state = WorkspaceOperationState.SUCCEEDED,
                    phase = WorkspacePhase.COMPLETE, idempotencyKey = "create:$boundary",
                    requestHash = "req_$boundary", operationPlan = plan,
                    createdAt = "2026-11-04T00:00:00Z", updatedAt = "2026-11-04T00:00:00Z"
                )

                assertFailsWith<IllegalStateException> {
                    workspaceStore.commitBinding(
                        sessionId = sessionId,
                        expectedWorkspaceId = null,
                        expectedGeneration = null,
                        newWorkspace = newWorkspace,
                        associatedWorktreeId = wsId,
                        operation = operation
                    )
                }

                // 原子性：Session / Workspace / operation 三者全未写入（无半写入）
                val sessionAfter = sessionStore.get(sessionId)!!
                assertNull(sessionAfter.workspaceId, "[$boundary] session workspace binding must be rolled back")
                assertNull(
                    sessionAfter.associatedWorktreeId,
                    "[$boundary] session associated worktree must be rolled back"
                )
                assertNull(workspaceStore.getWorkspace(wsId), "[$boundary] workspace must be rolled back")
                assertNull(workspaceStore.getOperation(opId), "[$boundary] operation must be rolled back")
            }
        }.onFailure { e ->
            fail("[$boundary] failed: ${e.message}")
        }
    }

    private fun bindingSuccessAndIdempotency() {
        runCatching {
            runBlocking {
                val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                MederiDataDatabase.Schema.create(driver)
                Mederi.migrateDataDb(driver)
                val dbm = xyz.mederi.store.db.DatabaseManager(driver)
                val sessionStore = SqliteSessionStore(dbm)
                val workspaceStore = SqliteWorkspaceStore(dbm)

                sessionStore.insert(
                    Session(
                        id = "sess_ok", projectId = "proj_1", title = "Ok",
                        status = SessionStatus.IDLE, agentMode = AgentMode.AUTONOMOUS,
                        aiModel = null, reasoningLevel = null,
                        createdAt = "2026-11-04T00:00:00Z", updatedAt = "2026-11-04T00:00:00Z"
                    )
                )

                val wsId = "ws_ok"
                val opId = "op_ok"
                val ws = Workspace(
                    id = wsId, projectId = "proj_1", kind = WorkspaceKind.MANAGED_WORKTREE,
                    checkoutRoot = "/worktrees/$wsId/checkout", projectRelativePath = "",
                    state = WorkspaceState.READY, generation = 1L,
                    createdAt = "2026-11-04T00:00:00Z", lastUsedAt = "2026-11-04T00:00:00Z"
                )
                val plan = WorkspaceOperationPlan.CreatePlan(
                    requestHash = "req_ok",
                    selection = WorkspaceSelection(WorkspaceKind.MANAGED_WORKTREE),
                    targetPath = "/worktrees/$wsId/checkout"
                )
                val op = WorkspaceOperation(
                    id = opId, projectId = "proj_1", sessionId = "sess_ok", workspaceId = wsId,
                    kind = WorkspaceOperationKind.CREATE, state = WorkspaceOperationState.SUCCEEDED,
                    phase = WorkspacePhase.COMPLETE, idempotencyKey = "create:req_ok",
                    requestHash = "req_ok", operationPlan = plan,
                    createdAt = "2026-11-04T00:00:00Z", updatedAt = "2026-11-04T00:00:00Z"
                )

                // 首次提交：成功且三记录全部落盘
                assertTrue(
                    workspaceStore.commitBinding("sess_ok", null, null, ws, wsId, op),
                    "first commitBinding must succeed"
                )
                assertEquals(wsId, sessionStore.get("sess_ok")!!.workspaceId, "session must be bound")
                assertEquals(wsId, sessionStore.get("sess_ok")!!.associatedWorktreeId, "session worktree must be bound")
                assertNotNull(workspaceStore.getWorkspace(wsId), "workspace must be persisted")
                assertNotNull(workspaceStore.getOperation(opId), "operation must be persisted")

                // 重复 requestId 重试（同一 operation id / idempotencyKey）：不产生重复行
                assertTrue(
                    workspaceStore.commitBinding("sess_ok", null, null, ws, wsId, op),
                    "repeated commitBinding with same requestId must still succeed"
                )
                assertEquals(
                    1, workspaceStore.listWorkspaces("proj_1").count { it.id == wsId },
                    "repeated requestId must not duplicate the workspace row"
                )
                assertEquals(
                    1, workspaceStore.listOperations("proj_1").count { it.id == opId },
                    "repeated requestId must not duplicate the operation row"
                )
                assertEquals(
                    1, workspaceStore.listOperations("proj_1").count { it.idempotencyKey == "create:req_ok" },
                    "repeated requestId must not duplicate the idempotency key"
                )
            }
        }.onFailure { e ->
            fail("binding success/idempotency failed: ${e.message}")
        }
    }

    /**
     * 准备门禁失败 + 同一 messageId 重试：已接收输入不得在历史中重复、模型只消费一次。
     *
     * 真实 SessionManagerImpl/TurnExecutor + 真实 WorkspaceManager（含 environment setup 门禁）+ 确定性 fake LLM。
     * 首次 send 在 setup 前持久化输入。setup 失败时模型不执行，修复后仅重试准备操作，
     * Core 以原消息 ID 继续并且只消费一次。
     */
    @Test
    fun preparationRetryConsumesAcceptedMessageExactlyOnce() = runBlocking {
        // 确定性 fake LLM：单轮纯文本回复（无工具调用），模型每消费一次输入即调用一次 executeStreaming。
        val fixture = WorkspaceRepairIntegrationTest.Fixture { _, _ ->
            emit(ai.koog.prompt.streaming.StreamFrame.TextDelta("ok"))
            emit(ai.koog.prompt.streaming.StreamFrame.TextComplete("ok"))
            emit(ai.koog.prompt.streaming.StreamFrame.End("stop",
                ai.koog.prompt.message.ResponseMetaInfo.create(ai.koog.utils.time.KoogClock.System)))
        }
        try {
            // 源仓库：一个初始提交
            val srcRepo = fixture.srcRepo
            git(srcRepo, "init")
            File(srcRepo, "README.md").writeText("source")
            git(srcRepo, "add", "README.md")
            git(srcRepo, "commit", "-m", "init")

            // setup 脚本经真实权限运行（FULL_ACCESS），避免默认 NEEDS_APPROVAL 拦截 setup 执行。
            fixture.sandboxManager.updateSettings(
                "proj", SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS)
            )

            fixture.setupProject()
            val sessionApi = SessionApiImpl(fixture.sessionManager)
            val workspaceApi = WorkspaceApiImpl(fixture.workspaceManager, fixture.projectManager,
                fixture.sessionManager::resumeWorkspaceInput)
            val agentConfig = AgentConfig(AgentMode.AUTONOMOUS, fixture.model)
            val sessionId = sessionApi.create(CreateSessionRequest(agentConfig, "proj")).id

            // 先创建 managed 工作区（REQUESTED），再登记环境（环境在 prepare 阶段解析，不要求已存在）。
            val envId = "env_retry"
            val ws = fixture.workspaceManager.createWorkspace(
                projectId = "proj", sourceRepoRoot = srcRepo.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD", environmentId = envId),
                sessionId = sessionId,
            )
            assertEquals(WorkspaceState.REQUESTED, ws.state, "新建 managed 工作区初始应为 REQUESTED")

            // 环境 setup 自洽控制成败：marker 位于工作区外（fixture.root，worktree remove --force 删除
            // checkout 后仍存活）。首次 prepare 先建 checkout（阶段 3）再跑 setup（阶段 5），此刻 marker
            // 缺失 → setup 失败；重试前测试写入 marker（在 fixture.root，不受 worktree remove 影响）→
            // setup 成功。FULL_ACCESS 下 ShellTools 可读外部绝对路径，故 marker 无需放在 workingDirectory 内。
            val marker = File(fixture.root, "retry-ready.flag")
            fixture.workspaceManager.saveProjectEnvironment(
                "proj",
                ProjectEnvironment(
                    id = envId, name = "RetryEnv", version = 0L,
                    setup = PlatformScript(default = "test -f ${marker.canonicalPath}")
                ),
                expectedVersion = 0L
            )

            val messageId = "msg_retry_stable"

            sessionApi.sendMessage(sessionId, SendMessageRequest(agentConfig,
                parts = listOf(MessagePart.Text("do the work")), messageId = messageId))
            val wsAfterFail = fixture.wsStore.getWorkspace(ws.id)
            assertNotEquals(WorkspaceState.READY, wsAfterFail?.state, "setup 失败不得打开 READY 门禁")
            val accepted = fixture.history.load(sessionId).single { it.id == messageId }
            assertEquals(ws.id, accepted.pendingWorkspaceInput?.workspaceId)
            assertTrue(fixture.history.load(sessionId).none { it.isFinalAnswer == true })
            assertEquals(0, fixture.llmCallCount, "未就绪前模型不得被消费")

            val failedOp = fixture.wsStore.listOperations("proj").single {
                it.kind == WorkspaceOperationKind.CREATE && it.state == WorkspaceOperationState.FAILED
            }

            // 仅重试准备。模型调用须由 Core 自动恢复，测试不再次发送消息。
            marker.writeText("ready")
            val retryOp = workspaceApi.retryWorkspacePreparation(sessionId, failedOp.id, "retry_from_button")
            assertEquals(WorkspaceOperationState.SUCCEEDED, retryOp.state, "通过重试 API 重新准备应成功")
            fixture.completed(sessionId)
            assertEquals(WorkspaceState.READY, fixture.wsStore.getWorkspace(ws.id)?.state,
                "重试后 managed 工作区应已 READY")
            val consumed = fixture.history.load(sessionId).single { it.id == messageId }
            assertEquals(accepted.parts, consumed.parts)
            assertNull(consumed.pendingWorkspaceInput)
            assertEquals(1, fixture.llmCallCount, "模型应只消费一次，实际 LLM 调用 ${fixture.llmCallCount}")
            assertEquals(retryOp.id,
                workspaceApi.retryWorkspacePreparation(sessionId, failedOp.id, "retry_from_button").id)
            assertEquals(1, fixture.llmCallCount, "重放重试请求不应再执行模型")
        } finally {
            fixture.close()
        }
    }

}
