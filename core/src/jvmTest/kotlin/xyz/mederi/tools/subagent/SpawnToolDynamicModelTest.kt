package xyz.mederi.tools.subagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.plan.Plan
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.Subtask
import xyz.mederi.plan.SubtaskStatus
import xyz.mederi.plan.VerificationSpec
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.InMemorySessionStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * spawn 工具动态读 session 模型（P2）行为锁定：
 * - session 有模型（计划批准时写入的新选择）→ spawn 用 session 值，而非工具构造时捕获的 turn 模型
 * - session 无模型/查不到 → fallback 到构造值
 * - spawn_researcher 同策略
 */
class SpawnToolDynamicModelTest {

    /** 记录 spawn 实收参数的 runner（ran 用于等后台协程实际执行完）。 */
    private class RecordingRunner : SubagentRunner {
        val ran = kotlinx.coroutines.CompletableDeferred<Unit>()
        var lastModel: AIModel? = null
        var lastReasoning: ReasoningLevel? = null
        override suspend fun run(
            task: String, briefing: String?, plan: String?, role: SubagentRole,
            directories: List<String>, aiModel: AIModel,
            reasoningLevel: ReasoningLevel, projectId: String, parentSessionId: String,
            apiKeyId: String?, planId: String?, executorSubtaskIndex: Int?,
            planStore: xyz.mederi.plan.PlanStore?,
            agentId: String?, onProgress: (suspend (activity: String, delta: String, toolName: String?, isMessage: Boolean) -> Unit)?
        ): String {
            lastModel = aiModel
            lastReasoning = reasoningLevel
            ran.complete(Unit)
            return "ok"
        }
    }

    private val tmpDir = Files.createTempDirectory("spawn-model-test")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmpDir.toFile().deleteRecursively()
    }

    private fun model(id: String) = AIModel(id = id, providerModelId = id, name = "Model $id")

    private suspend fun sessionStoreWith(model: AIModel?, reasoning: ReasoningLevel?): InMemorySessionStore {
        val store = InMemorySessionStore()
        val now = java.time.Instant.now().toString()
        store.insert(Session(
            id = "sess_parent", projectId = "p1", title = "t", status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = model, reasoningLevel = reasoning,
            env = emptyMap(), createdAt = now, updatedAt = now
        ))
        return store
    }

    /** 带单个带 spec 子任务的最小计划（已批准）。 */
    private fun planStoreWithSpecdSubtask(): Pair<PlanStore, String> {
        val planStore = PlanStore(listOf(tmpDir.toAbsolutePath().toString()))
        val plan = Plan(
            id = "plan_test", title = "t", sessionId = "sess_parent", overview = "o",
            subtasks = listOf(Subtask(
                index = 0, name = "s0", status = SubtaskStatus.PENDING,
                planDetail = "brief", spec = "1. do the thing",
                verification = VerificationSpec(command = "true")
            )),
            status = PlanStatus.APPROVED, createdAt = "2026-01-01T00:00:00Z",
            agentMode = AgentMode.AUTONOMOUS
        )
        planStore.save(plan)
        return planStore to plan.id
    }

    private fun newSpawnTool(
        runner: RecordingRunner,
        turnModel: AIModel,
        turnReasoning: ReasoningLevel,
        sessionStore: InMemorySessionStore?,
        planStore: PlanStore,
        subagentConfigManager: SubagentConfigManager? = null
    ) = SpawnAgentTool(
        subagentManager = SubagentManager(runner, scope),
        directories = emptyList(),
        aiModel = turnModel,
        reasoningLevel = turnReasoning,
        projectId = "p1",
        parentSessionId = "sess_parent",
        planStore = planStore,
        eventBus = null,
        apiKeyId = null,
        sessionStore = sessionStore,
        subagentConfigManager = subagentConfigManager
    )

    @Test
    fun `spawn uses session model when present`() = runBlocking {
        val runner = RecordingRunner()
        val (planStore, planId) = planStoreWithSpecdSubtask()
        // 构造时 turn 模型 = A；session（批准时写入）= B + HIGH —— spawn 必须用 B + HIGH
        val tool = newSpawnTool(
            runner, turnModel = model("A"), turnReasoning = ReasoningLevel.NONE,
            sessionStore = sessionStoreWith(model("B"), ReasoningLevel.HIGH),
            planStore = planStore
        )

        val result = tool.execute(SpawnAgentArgs(task = "do it", planId = planId, subtaskIndex = 0))
        kotlinx.coroutines.withTimeout(5_000) { runner.ran.await() }

        assert(result.contains("\"status\":\"RUNNING\"")) { "unexpected spawn result: $result" }
        assertEquals("B", runner.lastModel?.id, "spawn 应使用 session 现值 B（批准时刻选择），而非 turn 构造值 A")
        assertEquals(ReasoningLevel.HIGH, runner.lastReasoning, "spawn 应使用 session 推理档位 HIGH")
    }

    @Test
    fun `spawn falls back to turn model when session has none`() = runBlocking {
        val runner = RecordingRunner()
        val (planStore, planId) = planStoreWithSpecdSubtask()
        val tool = newSpawnTool(
            runner, turnModel = model("A"), turnReasoning = ReasoningLevel.MEDIUM,
            sessionStore = sessionStoreWith(model = null, reasoning = null),
            planStore = planStore
        )

        tool.execute(SpawnAgentArgs(task = "do it", planId = planId, subtaskIndex = 0))
        kotlinx.coroutines.withTimeout(5_000) { runner.ran.await() }

        assertEquals("A", runner.lastModel?.id, "session 无模型时 fallback 到构造值 A")
        assertEquals(ReasoningLevel.MEDIUM, runner.lastReasoning, "session 无档位时 fallback 到构造值 MEDIUM")
    }

    @Test
    fun `researcher spawn follows same session-first policy`() = runBlocking {
        val runner = RecordingRunner()
        val tool = SpawnResearcherTool(
            subagentManager = SubagentManager(runner, scope),
            directories = emptyList(),
            aiModel = model("A"),
            reasoningLevel = ReasoningLevel.NONE,
            projectId = "p1",
            parentSessionId = "sess_parent",
            apiKeyId = null,
            sessionStore = sessionStoreWith(model("C"), ReasoningLevel.LOW)
        )

        tool.execute(SpawnResearcherArgs(task = "investigate"))
        kotlinx.coroutines.withTimeout(5_000) { runner.ran.await() }

        assertEquals("C", runner.lastModel?.id, "researcher 同样以 session 现值优先")
        assertEquals(ReasoningLevel.LOW, runner.lastReasoning)
    }

    private class FakeProviderManager(
        private val models: Map<String, AIModel>
    ) : xyz.mederi.provider.ProviderManager {
        override suspend fun getModel(modelId: String): AIModel? = models[modelId]
        override suspend fun list(): List<xyz.mederi.provider.domain.model.Provider> = emptyList()
        override suspend fun listWithoutKeys(): List<xyz.mederi.provider.domain.model.Provider> = emptyList()
        override suspend fun get(id: String): xyz.mederi.provider.domain.model.Provider? = null
        override suspend fun require(id: String): xyz.mederi.provider.domain.model.Provider = throw NotImplementedError()
        override suspend fun create(name: String, type: xyz.mederi.provider.domain.model.ProviderType, baseUrl: String, reasoningParameter: xyz.mederi.provider.domain.model.ReasoningParameter?, responseSanitization: Boolean, modelsDevKey: String?): xyz.mederi.provider.domain.model.Provider = throw NotImplementedError()
        override suspend fun update(id: String, name: String?, baseUrl: String?, reasoningParameter: xyz.mederi.provider.domain.model.ReasoningParameter?, modelsDevKey: String?): xyz.mederi.provider.domain.model.Provider = throw NotImplementedError()
        override suspend fun delete(id: String) {}
        override suspend fun listKeys(providerId: String): List<xyz.mederi.provider.domain.model.ProviderApiKey> = emptyList()
        override suspend fun addKey(providerId: String, name: String, value: String, isDefault: Boolean): xyz.mederi.provider.domain.model.ProviderApiKey = throw NotImplementedError()
        override suspend fun deleteKey(providerId: String, keyId: String) {}
        override suspend fun setDefaultKey(providerId: String, keyId: String) {}
        override suspend fun getDefaultKeyValue(providerId: String): String? = null
        override suspend fun getKeyValue(providerId: String, keyId: String): String? = null
        override suspend fun listModels(providerId: String): List<AIModel> = models.values.toList()
        override suspend fun addModel(providerId: String, providerModelId: String, name: String, supportsReasoning: Boolean, reasoningLevel: ReasoningLevel, contextWindow: Int?, maxTokens: Int?, supportsImages: Boolean, reasoningLevels: List<ReasoningLevel>, isEnabled: Boolean, inputPricePerMillion: Double?, outputPricePerMillion: Double?): AIModel = throw NotImplementedError()
        override suspend fun addFetchedModel(providerId: String, merged: AIModel, isEnabled: Boolean): AIModel = throw NotImplementedError()
        override suspend fun applyRemoteMetadata(providerId: String, modelId: String, endpoint: xyz.mederi.provider.domain.model.RemoteModelInfo?, catalog: xyz.mederi.metadata.ModelMetadata?): AIModel = throw NotImplementedError()
        override suspend fun updateUserModel(providerId: String, modelId: String, name: String?, supportsReasoning: Boolean?, reasoningLevel: ReasoningLevel?, contextWindow: Int?, maxTokens: Int?, supportsImages: Boolean?, reasoningLevels: List<ReasoningLevel>?, isEnabled: Boolean?): AIModel = throw NotImplementedError()
        override suspend fun deleteModel(providerId: String, modelId: String) {}
        override suspend fun listAllModels(): List<AIModel> = models.values.toList()
        override suspend fun fetchRemoteModels(providerId: String): List<xyz.mederi.provider.domain.model.RemoteModelInfo> = emptyList()
    }

    @Test
    fun `spawn uses subagent config model when configured`() = runBlocking {
        val runner = RecordingRunner()
        val (planStore, planId) = planStoreWithSpecdSubtask()
        val customModel = model("CUSTOM")
        val configManager = SubagentConfigManager(
            xyz.mederi.store.InMemorySettingsStore(),
            FakeProviderManager(mapOf("CUSTOM" to customModel))
        )
        configManager.set(
            SubagentRole.EXECUTOR,
            xyz.mederi.domain.model.SubagentModelConfig(modelId = "CUSTOM", reasoningLevel = ReasoningLevel.MAX)
        )

        val tool = newSpawnTool(
            runner, turnModel = model("A"), turnReasoning = ReasoningLevel.NONE,
            sessionStore = sessionStoreWith(model("B"), ReasoningLevel.HIGH),
            planStore = planStore,
            subagentConfigManager = configManager
        )

        tool.execute(SpawnAgentArgs(task = "do it", planId = planId, subtaskIndex = 0))
        kotlinx.coroutines.withTimeout(5_000) { runner.ran.await() }

        assertEquals("CUSTOM", runner.lastModel?.id, "配置了独立模型时优先使用独立模型 CUSTOM，而非 session 模型 B")
        assertEquals(ReasoningLevel.MAX, runner.lastReasoning, "配置了独立推理等级时优先使用独立推理等级 MAX")
    }

    @Test
    fun `spawn falls back to session model when configured model is not found`() = runBlocking {
        val runner = RecordingRunner()
        val (planStore, planId) = planStoreWithSpecdSubtask()
        val configManager = SubagentConfigManager(
            xyz.mederi.store.InMemorySettingsStore(),
            FakeProviderManager(emptyMap())
        )
        configManager.set(
            SubagentRole.EXECUTOR,
            xyz.mederi.domain.model.SubagentModelConfig(modelId = "DELETED", reasoningLevel = ReasoningLevel.LOW)
        )

        val tool = newSpawnTool(
            runner, turnModel = model("A"), turnReasoning = ReasoningLevel.NONE,
            sessionStore = sessionStoreWith(model("B"), ReasoningLevel.HIGH),
            planStore = planStore,
            subagentConfigManager = configManager
        )

        tool.execute(SpawnAgentArgs(task = "do it", planId = planId, subtaskIndex = 0))
        kotlinx.coroutines.withTimeout(5_000) { runner.ran.await() }

        assertEquals("B", runner.lastModel?.id, "独立配置的模型不存在时应 fallback 到 session 模型 B")
        assertEquals(ReasoningLevel.LOW, runner.lastReasoning, "独立配置的推理等级仍然生效")
    }
}
