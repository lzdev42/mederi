package xyz.mederi.workspace

// 第四轮 N07 生产接线与修复核验（N07 全量验收已记录）
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.domain.model.*
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.*
import xyz.mederi.provider.infrastructure.koog.KoogClientFactory
import xyz.mederi.project.ProjectManager
import xyz.mederi.project.ProjectManagerImpl
import xyz.mederi.session.SessionManagerImpl
import xyz.mederi.store.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * RF-02 真实生产链路集成测试：WorkspaceManager / SessionManager / TurnExecutor / ToolFactory 均为真实对象，
 * 仅替换外部 LLM 为确定性 fake。验证 managed 首次 send 经准备门禁置 READY 后，模型 write_file 落在
 * managed checkout，原 Local 仓库不被触碰。
 */
class WorkspaceRepairIntegrationTest {
    companion object {
        val repoRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "gradlew").exists() }
    }

    private fun git(dir: File, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@test.invalid") + args)
            .directory(dir).redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out.trim()
    }

    /**
     * RF-04 故障注入 Store：在指定次数的 saveWorkspace 上抛 IOException（默认永不触发）。
     * 计数包含所有 saveWorkspace（创建/准备/归档/交接/恢复），测试在关键副作用前 arm 绝对阈值。
     */
    private class FaultInjectingWorkspaceStore(
        private val delegate: WorkspaceStore,
    ) : WorkspaceStore {
        private val saveWorkspaceCount = AtomicInteger(0)
        @Volatile
        var faultOnSaveWorkspaceCount: Int = Int.MAX_VALUE

        val savedWorkspaceCount: Int get() = saveWorkspaceCount.get()

        override suspend fun getWorkspace(id: String) = delegate.getWorkspace(id)
        override suspend fun listWorkspaces(projectId: String) = delegate.listWorkspaces(projectId)
        override suspend fun saveWorkspace(workspace: Workspace) {
            val count = saveWorkspaceCount.incrementAndGet()
            if (count == faultOnSaveWorkspaceCount) {
                throw java.io.IOException("Injected fault: saveWorkspace #$count")
            }
            delegate.saveWorkspace(workspace)
        }
        override suspend fun deleteWorkspace(id: String) = delegate.deleteWorkspace(id)
        override suspend fun getOperation(id: String) = delegate.getOperation(id)
        override suspend fun getOperationByIdempotencyKey(key: String) = delegate.getOperationByIdempotencyKey(key)
        override suspend fun listOperations(projectId: String) = delegate.listOperations(projectId)
        override suspend fun saveOperation(operation: WorkspaceOperation) = delegate.saveOperation(operation)
        override suspend fun deleteOperation(id: String) = delegate.deleteOperation(id)
        override suspend fun commitBinding(
            sessionId: String,
            expectedWorkspaceId: String?,
            expectedGeneration: Long?,
            newWorkspace: Workspace,
            associatedWorktreeId: String?,
            operation: WorkspaceOperation
        ): Boolean = delegate.commitBinding(
            sessionId, expectedWorkspaceId, expectedGeneration, newWorkspace, associatedWorktreeId, operation
        )
    }

    private class TestProviderManager(private val provider: Provider) : ProviderManager {
        private fun unused(): Nothing = throw UnsupportedOperationException("not used")
        override suspend fun list() = listOf(provider)
        override suspend fun listWithoutKeys() = listOf(provider)
        override suspend fun get(id: String) = provider
        override suspend fun require(id: String) = provider
        override suspend fun create(name: String, type: ProviderType, baseUrl: String, reasoningParameter: ReasoningParameter?, responseSanitization: Boolean, modelsDevKey: String?) = unused()
        override suspend fun update(id: String, name: String?, baseUrl: String?, reasoningParameter: ReasoningParameter?, modelsDevKey: String?) = unused()
        override suspend fun delete(id: String) = unused()
        override suspend fun listKeys(providerId: String) = provider.apiKeys
        override suspend fun addKey(providerId: String, name: String, value: String, isDefault: Boolean) = unused()
        override suspend fun deleteKey(providerId: String, keyId: String) = unused()
        override suspend fun setDefaultKey(providerId: String, keyId: String) = unused()
        override suspend fun getDefaultKeyValue(providerId: String) = "test-key"
        override suspend fun getKeyValue(providerId: String, keyId: String) = "test-key"
        override suspend fun listModels(providerId: String) = unused()
        override suspend fun addModel(providerId: String, providerModelId: String, name: String, supportsReasoning: Boolean, reasoningLevel: ReasoningLevel, contextWindow: Int?, maxTokens: Int?, supportsImages: Boolean, reasoningLevels: List<ReasoningLevel>, isEnabled: Boolean, inputPricePerMillion: Double?, outputPricePerMillion: Double?) = unused()
        override suspend fun addFetchedModel(providerId: String, merged: AIModel, isEnabled: Boolean) = unused()
        override suspend fun applyRemoteMetadata(providerId: String, modelId: String, endpoint: RemoteModelInfo?, catalog: xyz.mederi.metadata.ModelMetadata?) = unused()
        override suspend fun updateUserModel(providerId: String, modelId: String, name: String?, supportsReasoning: Boolean?, reasoningLevel: ReasoningLevel?, contextWindow: Int?, maxTokens: Int?, supportsImages: Boolean?, reasoningLevels: List<ReasoningLevel>?, isEnabled: Boolean?, optimizeForRequestCount: Boolean?) = unused()
        override suspend fun deleteModel(providerId: String, modelId: String) = unused()
        override suspend fun getModel(modelId: String) = unused()
        override suspend fun listAllModels() = unused()
        override suspend fun fetchRemoteModels(providerId: String) = unused()
    }

    /** 确定性 fake LLM：第 1 次 executeStreaming 发一次 write_file 工具调用，第 2 次发最终文本回复。 */
    private class ScriptClient(
        provider: Provider,
        private val script: suspend FlowCollector<StreamFrame>.(Prompt, Int) -> Unit,
    ) : LLMClient() {
        private val delegate = KoogClientFactory.create(provider, "test-key")
        val calls = AtomicInteger()
        val prompts = CopyOnWriteArrayList<Prompt>()
        override val clientName = "WorkspaceRepairIntegration"
        override fun llmProvider() = delegate.llmProvider()
        override fun close() = delegate.close()
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): ai.koog.prompt.message.Message.Assistant = error("expected true streaming")
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
            prompts.add(prompt)
            script(prompt, calls.incrementAndGet())
        }
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice = error("unused")
        override suspend fun moderate(prompt: Prompt, model: LLModel) = delegate.moderate(prompt, model)
        override suspend fun models() = emptyList<LLModel>()
        override fun getStandardJsonSchemaGenerator() = delegate.getStandardJsonSchemaGenerator()
        override fun getBasicJsonSchemaGenerator() = delegate.getBasicJsonSchemaGenerator()
    }

    internal class Fixture(script: suspend FlowCollector<StreamFrame>.(Prompt, Int) -> Unit) {
        val root = File(repoRoot, "temp/workspace-repair-${UUID.randomUUID()}").apply { mkdirs() }
        val srcRepo = File(root, "src-repo").apply { mkdirs() }
        val worktreesRoot = File(root, "worktrees").apply { mkdirs() }.absolutePath
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = InMemoryHistoryStore()
        val sessions = InMemorySessionStore()
        val projectStore = InMemoryProjectStore()
        val wsStore = InMemoryWorkspaceStore(sessionStore = sessions)
        val bus = MutableSharedFlow<MederiEvent>(extraBufferCapacity = 256)
        val events = CopyOnWriteArrayList<MederiEvent>()
        val model = AIModel(id = "test-model", providerModelId = "test-model", name = "Test", supportsImages = true)
        val provider = Provider("test-provider", "Test", ProviderType.OPENAI_CHAT, "http://unused.invalid/v1",
            models = listOf(model), apiKeys = listOf(ProviderApiKey("key", "Test", "***", true)))
        private val client = ScriptClient(provider, script)
        /** 模型 executeStreaming 调用次数——每次真实消费一条输入消息即 +1，用于断言"模型只消费一次"。 */
        val llmCallCount: Int get() = client.calls.get()
        val project = Project("proj", "Test", srcRepo.absolutePath, "now", "now")
        val projectManager: ProjectManager = ProjectManagerImpl(projectStore, sessions, history, null)
        val sandboxManager = xyz.mederi.tools.sandbox.SandboxManager(eventBus = bus)
        val workspaceManager = WorkspaceManager(
            workspaceStore = wsStore, sessionStore = sessions, historyStore = history,
            worktreesRoot = worktreesRoot, projectStore = projectStore,
            sandboxManager = sandboxManager,
        )
        val resolver = ExecutionWorkspaceResolver(wsStore, projectStore, sessions)
        val sessionManager = SessionManagerImpl(
            sessionStore = sessions, historyStore = history, projectManager = projectManager,
            providerManager = TestProviderManager(provider), eventBus = bus, sandboxManager = sandboxManager,
            workspaceResolver = resolver, workspacePreparer = workspaceManager::prepareSessionWorkspace,
            llmClientFactory = { _, _ -> client },
            leaseManager = workspaceManager.leaseManager,
        )

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.collect { events.add(it) } }
        }

        suspend fun setupProject() { projectStore.save(project) }

        val subagentRunner = xyz.mederi.tools.subagent.SubagentRunnerImpl(
            sandboxManager = sandboxManager, providerManager = TestProviderManager(provider),
            projectManager = projectManager, workspaceResolver = resolver,
            llmClientFactory = { _, _ -> client },
            leaseManager = workspaceManager.leaseManager,
        )

        suspend fun prepareManagedWorkspace(sessionId: String): Workspace {
            val ws = workspaceManager.createWorkspace(
                projectId = "proj", sourceRepoRoot = srcRepo.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD"),
                sessionId = sessionId,
            )
            workspaceManager.prepareWorkspace(ws.id, srcRepo.absolutePath, "prep_$sessionId")
            return wsStore.getWorkspace(ws.id)!!
        }

        suspend fun insertSession(id: String) {
            val now = java.time.Instant.now().toString()
            sessions.insert(Session(id = id, projectId = "proj", title = "Repair", status = SessionStatus.IDLE,
                agentMode = AgentMode.AUTONOMOUS, aiModel = model, reasoningLevel = ReasoningLevel.NONE,
                env = emptyMap(), createdAt = now, updatedAt = now))
        }

        suspend fun send(sessionId: String, messageId: String, text: String) {
            sessionManager.sendMessage(sessionId, SendMessageRequest(
                agentConfig = AgentConfig(AgentMode.AUTONOMOUS, model, ReasoningLevel.NONE),
                parts = listOf(MessagePart.Text(text)), messageId = messageId,
            ))
        }

        suspend fun completed(sessionId: String) = withTimeout(30_000) {
            while (events.none { it.type == EventType.MESSAGE_COMPLETED && it.sessionId == sessionId && it.payload["workCompletedAt"] != null }) delay(10)
        }

        suspend fun close() {
            scope.cancel()
            client.close()
            sandboxManager.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun managedFirstSendPreparesBeforeModelAndLeavesLocalUntouched() = runBlocking {
        val fixture = Fixture { _, call ->
            if (call == 1) {
                emit(StreamFrame.TextDelta("writing file in managed checkout"))
                emit(StreamFrame.TextComplete("writing file in managed checkout"))
                emit(StreamFrame.ToolCallComplete("w1", "write_file",
                    buildJsonObject { put("path", "written_by_model.txt"); put("content", "managed-write") }.toString()))
                emit(StreamFrame.End("tool_calls", ResponseMetaInfo.create(KoogClock.System)))
            } else {
                emit(StreamFrame.TextDelta("done"))
                emit(StreamFrame.TextComplete("done"))
                emit(StreamFrame.End("stop", ResponseMetaInfo.create(KoogClock.System)))
            }
        }
        try {
            // 源仓库：一个初始提交
            git(fixture.srcRepo, "init")
            File(fixture.srcRepo, "README.md").writeText("source")
            git(fixture.srcRepo, "add", "README.md")
            git(fixture.srcRepo, "commit", "-m", "init")
            val srcHeadBefore = git(fixture.srcRepo, "rev-parse", "HEAD")

            fixture.setupProject()
            fixture.insertSession("sess")
            // 创建 managed 工作区（REQUESTED），绑定会话——不预先 prepareWorkspace，验证首次 send 自身准备
            val ws = fixture.workspaceManager.createWorkspace(
                projectId = "proj", sourceRepoRoot = fixture.srcRepo.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD"),
                sessionId = "sess",
            )
            assertEquals(WorkspaceState.REQUESTED, ws.state, "新建 managed 工作区初始应为 REQUESTED")

            // 首次 send：准备门禁应把 REQUESTED 工作区准备到 READY，再执行模型工具
            fixture.send("sess", "msg1", "write a file")
            fixture.completed("sess")

            // 1. 工作区现在应为 READY
            val finalWs = fixture.wsStore.getWorkspace(ws.id)
            assertNotNull(finalWs)
            assertEquals(WorkspaceState.READY, finalWs.state, "首次 send 后 managed 工作区应已 READY")

            // 2. 模型 write_file 落在 managed checkout，内容正确
            val managedFile = File(finalWs.checkoutRoot, "written_by_model.txt")
            assertTrue(managedFile.exists(), "模型写入的文件应存在于 managed checkout: ${managedFile}")
            assertEquals("managed-write", managedFile.readText())

            // 3. 原 Local 仓库未被触碰——不存在同名文件，HEAD 不变
            assertFalse(File(fixture.srcRepo, "written_by_model.txt").exists(), "Local 仓库不应出现模型写入的文件")
            assertEquals(srcHeadBefore, git(fixture.srcRepo, "rev-parse", "HEAD"), "Local 仓库 HEAD 不应改变")
            assertEquals("source", File(fixture.srcRepo, "README.md").readText(), "Local 仓库内容不应改变")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleWorkspaceGrantFailsThroughActualToolFactory() = runBlocking {
        // 真实 FileSystemTools + ExecutionPermissions + VerifiedWorkspaceRoot 凭证路径（ToolFactory 构建 write_file 用的同一组对象）。
        // verifiedWorkspaceRootProvider 在 authorizeWrite（第 1 次调用，gen=1）与 checkGrant（第 2 次调用，gen=2）之间改变 generation，
        // 模拟授权后工作区迁移/代次变更 → grant 失效，工具写入被拒绝。
        val root = File(repoRoot, "temp/workspace-repair-stale-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val checkout = File(root, "checkout").apply { mkdirs() }
            val artifact = File(checkout, ".mederi").apply { mkdirs() }
            val manager = xyz.mederi.tools.sandbox.SandboxManager()
            val callCount = java.util.concurrent.atomic.AtomicInteger(0)
            // 第 1 次返回 generation=1（authorizeWrite 捕获进 grant），第 2 次返回 generation=2（checkGrant 比对失败）
            val provider: () -> VerifiedWorkspaceRoot = {
                val gen = if (callCount.incrementAndGet() == 1) 1L else 2L
                VerifiedWorkspaceRoot.create(
                    workspaceId = "ws_stale", projectId = "proj",
                    checkoutRoot = checkout.absolutePath, projectRelativePath = "",
                    artifactDirectory = artifact.absolutePath, generation = gen,
                )
            }
            val permissions = xyz.mederi.tools.sandbox.ExecutionPermissions(
                manager, listOf(checkout.absolutePath), "conv_stale", projectId = "proj",
                verifiedWorkspaceRootProvider = provider,
            )
            val fsTools = xyz.mederi.tools.FileSystemTools(
                allowedDirectories = listOf(checkout.absolutePath),
                executionPermissions = permissions,
            )
            val tool = fsTools.WriteFileTool()
            // 文件写入应在 checkGrant 阶段因工作区代次变更而失败
            val ex = assertFailsWith<xyz.mederi.api.exception.MederiStateException> {
                tool.execute(xyz.mederi.tools.FileSystemTools.WriteFileArgs(path = "stale.txt", content = "x"))
            }
            assertTrue(ex.message?.contains("Workspace changed") == true || ex.message?.contains("migrated") == true,
                "应报工作区代次变更，实际: ${ex.message}")
            // 文件不应被写入
            assertFalse(File(checkout, "stale.txt").exists(), "代次失效的 grant 不应完成写入")
            manager.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun subagentAndVerifyUsePreparedWorkspaceAndStableArtifactRoots() = runBlocking {
        // 子代理经 SubagentRunnerImpl（真实 workspaceResolver + inheritedWorkspace + llmClientFactory）继承父会话的 managed 工作区：
        // 其 write_file 落在 managed checkout，产物根稳定为 managed .mederi，原 Local 不被触碰。
        val fixture = Fixture { _, call ->
            if (call == 1) {
                emit(StreamFrame.TextDelta("subagent writing"))
                emit(StreamFrame.TextComplete("subagent writing"))
                emit(StreamFrame.ToolCallComplete("w2", "write_file",
                    buildJsonObject { put("path", "subagent_wrote.txt"); put("content", "sub-write") }.toString()))
                emit(StreamFrame.End("tool_calls", ResponseMetaInfo.create(KoogClock.System)))
            } else {
                emit(StreamFrame.TextDelta("subagent done"))
                emit(StreamFrame.TextComplete("subagent done"))
                emit(StreamFrame.End("stop", ResponseMetaInfo.create(KoogClock.System)))
            }
        }
        try {
            git(fixture.srcRepo, "init")
            File(fixture.srcRepo, "README.md").writeText("source")
            git(fixture.srcRepo, "add", "README.md")
            git(fixture.srcRepo, "commit", "-m", "init")

            fixture.setupProject()
            fixture.insertSession("sess")
            val ws = fixture.prepareManagedWorkspace("sess")
            assertEquals(WorkspaceState.READY, ws.state)

            // 子代理运行：继承父会话工作区，在 managed checkout 执行
            val report = fixture.subagentRunner.run(
                task = "write subagent_wrote.txt with content sub-write",
                briefing = "use write_file", plan = null,
                role = xyz.mederi.domain.model.SubagentRole.EXECUTOR,
                directories = listOf(ws.workingDirectory),
                aiModel = fixture.model, reasoningLevel = ReasoningLevel.NONE,
                projectId = "proj", parentSessionId = "sess", apiKeyId = null,
                planId = null, executorSubtaskIndex = null, planStore = null,
                agentId = "sub_test", onProgress = null,
            )

            // 1. 子代理 write_file 落在 managed checkout
            val managedFile = File(ws.checkoutRoot, "subagent_wrote.txt")
            assertTrue(managedFile.exists(), "子代理写入应落在 managed checkout: ${managedFile}")
            assertEquals("sub-write", managedFile.readText())

            // 2. 原 Local 未被触碰
            assertFalse(File(fixture.srcRepo, "subagent_wrote.txt").exists(), "Local 不应出现子代理写入的文件")
            assertEquals("source", File(fixture.srcRepo, "README.md").readText())

            // 3. 子代理产物根稳定为 managed checkout 内的 .mederi，非 Local .mederi
            val managedArtifact = java.io.File(ws.checkoutRoot, ".mederi")
            assertTrue(managedArtifact.absolutePath.startsWith(ws.checkoutRoot),
                "子代理产物根应稳定在 managed checkout 内: ${managedArtifact}")
            assertFalse(managedArtifact.absolutePath == java.io.File(fixture.srcRepo, ".mederi").absolutePath,
                "产物根不应回退到 Local .mederi")
        } finally {
            fixture.close()
        }
    }


    @Test
    fun sourceEditAfterSnapshotIsNotDiscardedAtCleanup() = runBlocking {
        // RF-05：snapshot 之后、CLEAN_SOURCE 之前外部编辑了源 tracked 文件，清源不得丢弃新内容、不得假成功。
        val root = File(repoRoot, "temp/workspace-repair-rf05-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "source").apply { mkdirs() }
            git(source, "init")
            File(source, "tracked.txt").writeText("snapshot value")
            git(source, "add", "tracked.txt")
            git(source, "commit", "-m", "init")
            val headOid = git(source, "rev-parse", "HEAD")

            // 捕获 transfer 快照（真实 SnapshotService）
            val snapRoot = File(root, "snapshots").apply { mkdirs() }
            val service = WorkspaceSnapshotService(snapRoot)
            val manifest = service.captureSnapshot(source.absolutePath, "ws", "rf05", SnapshotCaptureType.TRANSFER)

            // snapshot 之后外部编辑源 tracked 文件（模拟用户/IDE 在复制或 setup 期间修改）
            File(source, "tracked.txt").writeText("external newer value")

            // 清源：应检测到 postimage 不一致 → RECOVERY_REQUIRED，不丢弃新内容
            val wsStore = InMemoryWorkspaceStore()
            val mgr = WorkspaceManager(workspaceStore = wsStore, sessionStore = InMemorySessionStore(),
                historyStore = InMemoryHistoryStore(), worktreesRoot = File(root, "wt").apply { mkdirs() }.absolutePath)
            val ex = assertFailsWith<xyz.mederi.api.exception.MederiWorkspaceException> {
                mgr.cleanTransferredFromSource(source, headOid, manifest)
            }
            assertEquals(WorkspaceErrorCode.RECOVERY_REQUIRED, ex.code, "应报 RECOVERY_REQUIRED，实际: ${ex.code}")

            // 新内容保留，未被恢复成旧 HEAD 内容
            assertEquals("external newer value", File(source, "tracked.txt").readText(),
                "清源不得丢弃 snapshot 之后的外部编辑")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveRestoreRemainsRegisteredAndCanBeArchivedAgain() = runBlocking {
        // RF-06：真实 创建→归档→恢复→校验 common-dir/worktree list→commit 并从原仓库解析对象→再次归档/恢复。
        // 恢复的 checkout 必须是原仓库 linked worktree（含 tracked/binary staged/untracked/ignored），而非独立 git init 仓库。
        val root = File(repoRoot, "temp/workspace-repair-rf06-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "source").apply { mkdirs() }
            git(source, "init")
            File(source, "tracked.txt").writeText("original")
            git(source, "add", "tracked.txt")
            git(source, "commit", "-m", "init")
            val originalCommonDir = File(git(source, "rev-parse", "--path-format=absolute", "--git-common-dir")).canonicalPath

            val wsStore = InMemoryWorkspaceStore()
            val mgr = WorkspaceManager(
                workspaceStore = wsStore,
                sessionStore = InMemorySessionStore(),
                historyStore = InMemoryHistoryStore(),
                worktreesRoot = File(root, "wt").apply { mkdirs() }.absolutePath,
            )

            // 真实创建 managed 工作区并准备（prepare 内部执行 git worktree add --detach）
            val created = mgr.createWorkspace(
                projectId = "proj",
                sourceRepoRoot = source.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD"),
            )
            mgr.prepareWorkspace(created.id, source.absolutePath, "rf06_prep")

            var ws = wsStore.getWorkspace(created.id)!!
            assertEquals(WorkspaceState.READY, ws.state)
            val checkout1 = File(ws.checkoutRoot)
            assertTrue(File(checkout1, ".git").exists(), "managed checkout 应是 git worktree: ${checkout1}")

            // 写入快照要覆盖的内容：tracked 修改 + stage、binary、untracked、ignored（.gitignore）
            File(checkout1, "tracked.txt").writeText("workspace modified")
            val binaryBytes = byteArrayOf(0x00, 0x01, 0x02, 0x7F.toByte(), 0x80.toByte(), 0xFF.toByte())
            File(checkout1, "data.bin").writeBytes(binaryBytes)
            File(checkout1, "untracked.txt").writeText("untracked content")
            File(checkout1, ".gitignore").writeText("ignored.log\n")
            File(checkout1, "ignored.log").writeText("ignored content")
            git(checkout1, "add", "tracked.txt", "data.bin")

            // 归档（PREIMAGE 快照 + git worktree remove --force）
            mgr.archiveWorkspace(ws.id, ArchiveWorkspaceInput(requestId = "rf06_archive_1"))
            ws = wsStore.getWorkspace(created.id)!!
            assertEquals(WorkspaceState.ARCHIVED, ws.state)
            assertFalse(checkout1.exists(), "归档应移除 checkout 目录")

            // 恢复
            mgr.restoreWorkspace(ws.id, RestoreWorkspaceInput(requestId = "rf06_restore_1"))
            ws = wsStore.getWorkspace(created.id)!!
            assertEquals(WorkspaceState.READY, ws.state)
            val restored = File(ws.checkoutRoot)
            assertTrue(restored.exists(), "恢复后 checkout 应存在: ${restored}")

            // 1. linked worktree 注册验证：原仓库 worktree list 出现恢复路径，且 common-dir 指向原仓库
            val worktreeList = git(source, "worktree", "list")
            assertTrue(worktreeList.contains(restored.canonicalPath),
                "恢复的 checkout 应出现在原仓库 worktree list 中: $worktreeList")
            val commonDirFromRestored = File(git(restored, "rev-parse", "--path-format=absolute", "--git-common-dir")).canonicalPath
            assertEquals(originalCommonDir, commonDirFromRestored,
                "恢复的 checkout 必须与原仓库共享 common-dir，而不是独立 git init 仓库")

            // 2. 内容验证：tracked/binary/untracked/ignored
            assertEquals("workspace modified", File(restored, "tracked.txt").readText(), "tracked 修改应恢复")
            assertContentEquals(binaryBytes, File(restored, "data.bin").readBytes(), "binary 文件应恢复")
            assertEquals("untracked content", File(restored, "untracked.txt").readText(), "untracked 文件应恢复")
            assertEquals("ignored.log\n", File(restored, ".gitignore").readText(), ".gitignore 应恢复")
            assertEquals("ignored content", File(restored, "ignored.log").readText(), "ignored 文件应恢复")
            val staged = git(restored, "diff", "--cached", "--name-only")
            assertTrue(staged.contains("data.bin"), "staged data.bin 应随 index.bin 恢复, staged: $staged")
            assertTrue(staged.contains("tracked.txt"), "staged tracked.txt 应随 index.bin 恢复, staged: $staged")

            // 3. 恢复的 checkout 可提交，且原仓库能解析该对象（证明共享同一个对象库）
            git(restored, "add", "untracked.txt")
            git(restored, "commit", "-m", "restored commit")
            val newCommit = git(restored, "rev-parse", "HEAD")
            assertEquals("commit", git(source, "cat-file", "-t", newCommit),
                "原仓库必须能解析恢复 worktree 中提交的对象: $newCommit")

            // 4. 再次归档→恢复：仍注册、内容仍正确
            mgr.archiveWorkspace(ws.id, ArchiveWorkspaceInput(requestId = "rf06_archive_2"))
            ws = wsStore.getWorkspace(created.id)!!
            assertEquals(WorkspaceState.ARCHIVED, ws.state)
            mgr.restoreWorkspace(ws.id, RestoreWorkspaceInput(requestId = "rf06_restore_2"))
            ws = wsStore.getWorkspace(created.id)!!
            assertEquals(WorkspaceState.READY, ws.state)

            val restored2 = File(ws.checkoutRoot)
            assertTrue(restored2.exists(), "二次恢复后 checkout 应存在: ${restored2}")
            val worktreeList2 = git(source, "worktree", "list")
            assertTrue(worktreeList2.contains(restored2.canonicalPath),
                "二次恢复的 checkout 仍应注册在原仓库 worktree list 中: $worktreeList2")
            val commonDirFromRestored2 = File(git(restored2, "rev-parse", "--path-format=absolute", "--git-common-dir")).canonicalPath
            assertEquals(originalCommonDir, commonDirFromRestored2,
                "二次恢复的 checkout 仍必须与原仓库共享 common-dir")
            assertEquals("workspace modified", File(restored2, "tracked.txt").readText(), "二次恢复后 tracked 内容应保留")
            assertContentEquals(binaryBytes, File(restored2, "data.bin").readBytes(), "二次恢复后 binary 内容应保留")
            assertEquals("untracked content", File(restored2, "untracked.txt").readText(), "二次恢复后 untracked 内容应保留")
            assertEquals("ignored content", File(restored2, "ignored.log").readText(), "二次恢复后 ignored 内容应保留")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveWaitsForRealExecutionAndIncludesLateWrites() = runBlocking {
        // RF-07：归档必须等待活跃 execution lease 归零后才快照——快照因此包含等待期间的 late writes；
        // 租约持有期间 checkout 不得被删除。
        val root = File(repoRoot, "temp/workspace-repair-rf07-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "source").apply { mkdirs() }
            git(source, "init")
            File(source, "initial.txt").writeText("initial")
            git(source, "add", "initial.txt")
            git(source, "commit", "-m", "init")

            val wsStore = InMemoryWorkspaceStore()
            val mgr = WorkspaceManager(
                workspaceStore = wsStore,
                sessionStore = InMemorySessionStore(),
                historyStore = InMemoryHistoryStore(),
                worktreesRoot = File(root, "wt").apply { mkdirs() }.absolutePath,
            )

            // 创建 + 准备 managed 工作区
            val created = mgr.createWorkspace(
                projectId = "proj",
                sourceRepoRoot = source.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD"),
            )
            mgr.prepareWorkspace(created.id, source.absolutePath, "rf07_prep")
            var ws = wsStore.getWorkspace(created.id)!!
            val checkout = File(ws.checkoutRoot)

            // 先写入一份内容
            File(checkout, "work.txt").writeText("before lease")

            // 获取执行租约（模拟活跃 turn 执行）
            val lease = mgr.leaseManager.acquireExecutionLease(ws.checkoutRoot)

            // 在协程中发起归档——应阻塞等待租约
            val archiveResult = CompletableDeferred<WorkspaceOperation>()
            val archiveException = CompletableDeferred<Throwable>()
            val archiveFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val archiveJob = launch {
                try {
                    val op = mgr.archiveWorkspace(ws.id, ArchiveWorkspaceInput(requestId = "rf07_archive"))
                    archiveResult.complete(op)
                } catch (e: Throwable) {
                    archiveException.complete(e)
                    archiveFailure.set(e)
                }
            }

            // 给归档时间进入等待
            delay(200)

            // 归档不应已完成（仍在等待租约）
            assertFalse(archiveResult.isCompleted, "Archive should be waiting for execution lease")
            assertFalse(archiveException.isCompleted, "Archive should not have thrown yet")
            // 租约持有期间 checkout 必须仍存在（未被删除）
            assertTrue(checkout.exists(), "Checkout must not be deleted while execution lease is held")

            // late write：在归档等待期间写入新内容
            File(checkout, "work.txt").writeText("late write during lease")
            File(checkout, "late_file.txt").writeText("late file content")

            // 释放租约——归档应继续执行
            lease.close()

            // 等待归档完成
            withTimeout(15000) {
                archiveJob.join()
            }

            // 归档应成功
            assertFalse(archiveException.isCompleted, "Archive should not have thrown: ${archiveFailure.get()}")
            assertTrue(archiveResult.isCompleted, "Archive should have completed after lease release")

            // 快照必须包含 late writes
            ws = wsStore.getWorkspace(created.id)!!
            assertEquals(WorkspaceState.ARCHIVED, ws.state)
            val snapshotId = ws.lastSnapshotId!!

            // 恢复并验证内容
            mgr.restoreWorkspace(ws.id, RestoreWorkspaceInput(requestId = "rf07_restore"))
            ws = wsStore.getWorkspace(created.id)!!
            val restored = File(ws.checkoutRoot)
            assertEquals("late write during lease", File(restored, "work.txt").readText(),
                "Snapshot must include late write made while archive was waiting")
            assertEquals("late file content", File(restored, "late_file.txt").readText(),
                "Snapshot must include late file made while archive was waiting")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveRecoveryHandlesRemoveBeforeDatabaseCommit() = runBlocking {
        // RF-04：归档在 git worktree remove 之后、saveWorkspace(ARCHIVED) 之前注入故障（remove 已发生但 DB 未提交）。
        // RUNNING 操作日志必须由生产 archiveWorkspace 在 side-effect 前持久化（不能手工 save），
        // 重启扫描（同一 Store 新建 Manager）据此完成归档并落 snapshotId。
        val root = File(repoRoot, "temp/workspace-repair-rf04-archive-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "source").apply { mkdirs() }
            git(source, "init")
            File(source, "tracked.txt").writeText("original")
            git(source, "add", "tracked.txt")
            git(source, "commit", "-m", "init")

            val sessions = InMemorySessionStore()
            val delegate = InMemoryWorkspaceStore(sessionStore = sessions)
            val faultStore = FaultInjectingWorkspaceStore(delegate)
            val worktreesRoot = File(root, "wt").apply { mkdirs() }.absolutePath
            val snapshotsRoot = File(root, "snapshots").apply { mkdirs() }.absolutePath
            val mgr1 = WorkspaceManager(
                workspaceStore = faultStore,
                sessionStore = sessions,
                historyStore = InMemoryHistoryStore(),
                worktreesRoot = worktreesRoot,
                snapshotsRoot = snapshotsRoot,
            )

            // 真实创建 + 准备 managed 工作区
            val created = mgr1.createWorkspace(
                projectId = "proj",
                sourceRepoRoot = source.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD"),
            )
            mgr1.prepareWorkspace(created.id, source.absolutePath, "rf04_prep")
            val ws = delegate.getWorkspace(created.id)!!
            assertEquals(WorkspaceState.READY, ws.state)
            val checkout = File(ws.checkoutRoot)
            assertTrue(checkout.exists(), "prepared checkout 应存在: ${checkout}")

            // 注入故障：归档 MANAGED 分支第 2 次 saveWorkspace = ARCHIVED 提交（remove 之后、DB 提交之前）。
            // 第 1 次 saveWorkspace = ARCHIVING 标记；RUNNING 日志/快照/remove 均已在第 2 次之前完成。
            faultStore.faultOnSaveWorkspaceCount = faultStore.savedWorkspaceCount + 2

            val ex = assertFailsWith<java.io.IOException> {
                mgr1.archiveWorkspace(ws.id, ArchiveWorkspaceInput(requestId = "rf04_archive"))
            }
            assertTrue(ex.message?.contains("saveWorkspace") == true, "故障应来自注入的 saveWorkspace, 实际: ${ex.message}")

            // 1. 生产代码已持久 RUNNING 操作日志（含快照捕获后的 snapshotId）
            val runningOp = delegate.listOperations("proj").find { it.kind == WorkspaceOperationKind.ARCHIVE }
            assertNotNull(runningOp, "生产 archiveWorkspace 必须持久 RUNNING 操作日志")
            assertEquals(WorkspaceOperationState.RUNNING, runningOp.state)
            val plan = runningOp.operationPlan as WorkspaceOperationPlan.ArchivePlan
            assertNotNull(plan.snapshotId, "RUNNING 日志须含 snapshotId")
            assertEquals(plan.snapshotId, runningOp.sourceSnapshotId)

            // 2. 工作区仍为 ARCHIVING（ARCHIVED 数据库提交未发生）
            assertEquals(WorkspaceState.ARCHIVING, delegate.getWorkspace(ws.id)!!.state)

            // 3. checkout 已被 git worktree remove 移除
            assertFalse(checkout.exists(), "git worktree remove 应已移除 checkout")

            // 重启扫描：同一 Store 新建 Manager（模拟 close/reopen），preview cache 无关
            val mgr2 = WorkspaceManager(
                workspaceStore = faultStore,
                sessionStore = sessions,
                historyStore = InMemoryHistoryStore(),
                worktreesRoot = worktreesRoot,
                snapshotsRoot = snapshotsRoot,
            )
            val recovered = mgr2.scanAndRecoverPendingOperations("proj")
            assertEquals(1, recovered.size)
            assertEquals(WorkspaceOperationState.SUCCEEDED, recovered[0].state, "恢复应完成归档")
            assertEquals(WorkspaceOperationKind.ARCHIVE, recovered[0].kind)

            // 4. 工作区已 ARCHIVED，snapshotId 落库，generation 递增一次
            val wsFinal = delegate.getWorkspace(ws.id)!!
            assertEquals(WorkspaceState.ARCHIVED, wsFinal.state)
            assertEquals(plan.snapshotId, wsFinal.lastSnapshotId)
            assertEquals(ws.generation + 1, wsFinal.generation)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun handoffJournalSurvivesEveryRealMutationBoundary() = runBlocking {
        // RF-04：executeHandoff 必须在第一个 git 副作用之前由生产代码持久 RUNNING + 完整 HandoffPlan，
        // 快照阶段完成后回填 snapshotIds/phaseRecords。在 COMMIT_BINDING 注入故障后，
        // 重启扫描（同一 Store 新建 Manager，preview cache 已丢失）只依据持久日志 + 文件/Git 状态完成交接。
        val root = File(repoRoot, "temp/workspace-repair-rf04-handoff-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "source").apply { mkdirs() }
            git(source, "init")
            git(source, "checkout", "-b", "main")
            File(source, "tracked.txt").writeText("original")
            git(source, "add", "tracked.txt")
            git(source, "commit", "-m", "init")

            val sessions = InMemorySessionStore()
            val delegate = InMemoryWorkspaceStore(sessionStore = sessions)
            val faultStore = FaultInjectingWorkspaceStore(delegate)
            val worktreesRoot = File(root, "wt").apply { mkdirs() }.absolutePath
            val snapshotsRoot = File(root, "snapshots").apply { mkdirs() }.absolutePath
            val mgr1 = WorkspaceManager(
                workspaceStore = faultStore,
                sessionStore = sessions,
                historyStore = InMemoryHistoryStore(),
                worktreesRoot = worktreesRoot,
                snapshotsRoot = snapshotsRoot,
            )

            val now = java.time.Instant.now().toString()
            val session = Session(
                id = "sess_rf04", projectId = "proj", title = "RF04",
                status = SessionStatus.IDLE, agentMode = AgentMode.AUTONOMOUS,
                aiModel = null, reasoningLevel = null, createdAt = now, updatedAt = now,
            )
            sessions.insert(session)

            // LOCAL 源工作区并绑定会话
            val localWs = mgr1.createWorkspace(
                projectId = "proj",
                sourceRepoRoot = source.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL),
                sessionId = session.id,
            )
            assertEquals(WorkspaceState.READY, localWs.state)

            // 源内容：handoff 要携带的未跟踪改动（真实文件，非 mock）
            File(source, "feature.txt").writeText("carried content")

            // preview：真实注册 managed 目标 worktree 并预检
            val preview = mgr1.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
            val targetWs = delegate.getWorkspace(preview.target.workspace.id)!!
            assertEquals(WorkspaceKind.MANAGED_WORKTREE, targetWs.kind)
            assertTrue(File(targetWs.checkoutRoot).exists(), "目标 managed worktree 应已注册")

            // 注入故障：executeHandoff 第 3 次 saveWorkspace = COMMIT_BINDING 的源 READY 提交
            // （前 2 次 = 源/目标 TRANSFERRING；RUNNING 日志与 phaseRecords 更新均在第 3 次之前完成）
            faultStore.faultOnSaveWorkspaceCount = faultStore.savedWorkspaceCount + 3

            val ex = assertFailsWith<java.io.IOException> {
                mgr1.executeHandoff(
                    session.id,
                    HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "rf04_ho"),
                )
            }
            assertTrue(ex.message?.contains("saveWorkspace") == true, "故障应来自注入的 saveWorkspace, 实际: ${ex.message}")

            // 1. 生产代码已持久 RUNNING 操作日志，含完整 snapshotIds
            val runningOp = delegate.listOperations("proj").find { it.kind == WorkspaceOperationKind.HANDOFF }
            assertNotNull(runningOp, "生产 executeHandoff 必须持久 RUNNING 操作日志")
            assertEquals(WorkspaceOperationState.RUNNING, runningOp.state)
            val plan = runningOp.operationPlan as WorkspaceOperationPlan.HandoffPlan
            assertNotNull(plan.sourceTransferSnapshotId, "日志须含 sourceTransferSnapshotId")
            assertNotNull(plan.sourcePreimageSnapshotId, "日志须含 sourcePreimageSnapshotId")
            assertNotNull(plan.targetPreimageSnapshotId, "日志须含 targetPreimageSnapshotId")
            assertEquals(plan.sourceTransferSnapshotId, runningOp.sourceSnapshotId)

            // 2. phaseRecords 记录已完成阶段（SNAPSHOT/CLEAN_SOURCE 均带 finishedAt）
            val phases = runningOp.phaseRecords.map { it.phase }
            assertTrue(WorkspacePhase.SNAPSHOT in phases, "phaseRecords 应含 SNAPSHOT, 实际: $phases")
            assertTrue(WorkspacePhase.CLEAN_SOURCE in phases, "phaseRecords 应含 CLEAN_SOURCE, 实际: $phases")
            assertTrue(runningOp.phaseRecords.all { it.finishedAt != null }, "已完成阶段须有 finishedAt")

            // 3. 重启扫描：同一 Store 新建 Manager（preview cache 已丢失），仅靠日志 + Git 状态恢复
            val mgr2 = WorkspaceManager(
                workspaceStore = faultStore,
                sessionStore = sessions,
                historyStore = InMemoryHistoryStore(),
                worktreesRoot = worktreesRoot,
                snapshotsRoot = snapshotsRoot,
            )
            val recovered = mgr2.scanAndRecoverPendingOperations("proj")
            assertEquals(1, recovered.size)
            assertEquals(WorkspaceOperationState.SUCCEEDED, recovered[0].state,
                "目标已核验匹配 accepted snapshot 且源已 clean，恢复应幂等完成 COMMIT_BINDING")

            // 会话绑定迁移到目标工作区
            val sessAfter = sessions.get(session.id)!!
            assertEquals(targetWs.id, sessAfter.workspaceId, "恢复后会话应绑定目标工作区")
        } finally {
            root.deleteRecursively()
        }
    }
}
