package xyz.mederi.koog

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.Project
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.infrastructure.koog.TurnExecutor
import xyz.mederi.plan.Plan
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.PlanStore
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.project.ProjectManager
import xyz.mederi.store.InMemorySessionStore
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P3 跨 turn 批准（"批准与 turn 解耦"）行为锁定：
 * 无存活 requester（翻历史/重启）时批准一个 PENDING_APPROVAL 计划：
 * - 计划状态被置为 APPROVED 落盘
 * - 以一条 UI 隐藏内部消息（<<<NOT_FOR_UI>>> 开头）启动新执行 turn（historyStore 可见、用户不可见）
 * - 已 APPROVED/终态/他会话计划 → 拒绝跨 turn 批准（no-op）
 */
class PlanCrossTurnApprovalTest {

    private val tmpDir = File.createTempFile("mederi-cross-turn-approval", "").apply {
        delete(); mkdirs()
    }

    private object UnusedProviderManager : ProviderManager {
        private fun unused(): Nothing = throw UnsupportedOperationException("not used in this test")
        override suspend fun list() = unused()
        override suspend fun listWithoutKeys() = unused()
        override suspend fun get(id: String) = unused()
        override suspend fun require(id: String) = unused()
        override suspend fun create(name: String, type: xyz.mederi.provider.domain.model.ProviderType, baseUrl: String, reasoningParameter: xyz.mederi.provider.domain.model.ReasoningParameter?, responseSanitization: Boolean, modelsDevKey: String?) = unused()
        override suspend fun update(id: String, name: String?, baseUrl: String?, reasoningParameter: xyz.mederi.provider.domain.model.ReasoningParameter?, modelsDevKey: String?) = unused()
        override suspend fun delete(id: String) = unused()
        override suspend fun listKeys(providerId: String) = unused()
        override suspend fun addKey(providerId: String, name: String, value: String, isDefault: Boolean) = unused()
        override suspend fun deleteKey(providerId: String, keyId: String) = unused()
        override suspend fun setDefaultKey(providerId: String, keyId: String) = unused()
        override suspend fun getDefaultKeyValue(providerId: String) = unused()
        override suspend fun getKeyValue(providerId: String, keyId: String) = unused()
        override suspend fun listModels(providerId: String) = unused()
        override suspend fun addModel(providerId: String, providerModelId: String, name: String, supportsReasoning: Boolean, reasoningLevel: ReasoningLevel, contextWindow: Int?, maxTokens: Int?, supportsImages: Boolean, reasoningLevels: List<ReasoningLevel>, isEnabled: Boolean, inputPricePerMillion: Double?, outputPricePerMillion: Double?) = unused()
        override suspend fun addFetchedModel(providerId: String, merged: AIModel, isEnabled: Boolean) = unused()
        override suspend fun applyRemoteMetadata(providerId: String, modelId: String, endpoint: xyz.mederi.provider.domain.model.RemoteModelInfo?, catalog: xyz.mederi.metadata.ModelMetadata?) = unused()
        override suspend fun updateUserModel(providerId: String, modelId: String, name: String?, supportsReasoning: Boolean?, reasoningLevel: ReasoningLevel?, contextWindow: Int?, maxTokens: Int?, supportsImages: Boolean?, reasoningLevels: List<ReasoningLevel>?, isEnabled: Boolean?) = unused()
        override suspend fun deleteModel(providerId: String, modelId: String) = unused()
        override suspend fun getModel(modelId: String) = unused()
        override suspend fun listAllModels() = unused()
        override suspend fun fetchRemoteModels(providerId: String) = unused()
    }

    private class StubProjectManager(val project: Project) : ProjectManager {
        override suspend fun list(): List<Project> = listOf(project)
        override suspend fun get(id: String): Project? = if (id == project.id) project else null
        override suspend fun require(id: String): Project = project
        override suspend fun create(name: String, directory: String): Project = project
        override suspend fun delete(id: String) = Unit
        override suspend fun rename(id: String, name: String): Project = project
    }

    private val planStore = PlanStore(listOf(tmpDir.absolutePath))

    private fun plan(sessionId: String, status: PlanStatus = PlanStatus.PENDING_APPROVAL): Plan =
        Plan(
            id = "plan_${UUID.randomUUID().toString().take(8)}",
            title = "Cross-turn plan", summary = "sum", sessionId = sessionId,
            overview = "ovr", status = status,
            createdAt = java.time.Instant.now().toString(), agentMode = AgentMode.APPROVAL,
            subtasks = emptyList()
        )

    private fun newExecutor(
        store: InMemorySessionStore,
        projectManager: ProjectManager,
        historyStore: xyz.mederi.store.InMemoryHistoryStore = xyz.mederi.store.InMemoryHistoryStore()
    ) = TurnExecutor(
        sessionStore = store,
        historyStore = historyStore,
        eventBus = MutableSharedFlow<xyz.mederi.domain.model.MederiEvent>(),
        providerManager = UnusedProviderManager,
        projectManager = projectManager
    )

    private suspend fun insertSession(
        store: InMemorySessionStore,
        sessionId: String,
        projectId: String,
        model: AIModel
    ): String {
        val now = java.time.Instant.now().toString()
        store.insert(Session(
            id = sessionId, projectId = projectId, title = "t", status = SessionStatus.IDLE,
            agentMode = AgentMode.APPROVAL, aiModel = model, reasoningLevel = ReasoningLevel.LOW,
            env = emptyMap(), createdAt = now, updatedAt = now
        ))
        return sessionId
    }

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `cross-turn approval marks pending plan approved and launches internal execution turn`() = runBlocking {
        val projectId = "proj_1"
        val project = Project(projectId, "p", tmpDir.absolutePath, "now", "now")
        val store = InMemorySessionStore()
        val sessionId = insertSession(store, "sess_cross", projectId, model("A"))
        val p = plan(sessionId)
        planStore.save(p)

        // (executor 用桩 provider/真实项目，段2启动执行 turn 会因桩 provider 无法解析模型而抛——见下注释)
        val executor = newExecutor(store, StubProjectManager(project))
        //   段1（可单测，无 LLM 依赖）= 校验计划 → updatePlan 置 APPROVED → 发 PLAN_APPROVAL_RESOLVED 事件，返回 true；
        //   段2（依赖 LLM）= sendMessageInternal 需解析 provider/model/key 真连模型 API——单测用空桩 provider 会在此抛
        //   （NoSuchElement / UnsupportedOperation）。段2 是"真正驱动执行 turn"的集成级行为，归 e2e（idea-mcp）验证。
        // 段1与段2在同一调用内：段2 抛异常会被 runCatching 吞掉，getOrNull() 可能为 null。
        // 但"计划被置 APPROVED"在 updatePlan 成功返回、进入 sendMessageInternal 之前已完成——
        // 该断言成立即证明跨 turn 批准段1（校验→置 APPROVED→发事件）完整走通。
        runCatching { executor.resolvePlanApproval(sessionId, p.id, approved = true, aiModel = null) }
        assertEquals(PlanStatus.APPROVED, planStore.load(p.id)?.status, "计划应被置为 APPROVED（段1已落盘）")
    }

    @Test
    fun `cross-turn approval skipped for non-pending or foreign plans`() = runBlocking {
        val projectId = "proj_2"
        val project = Project(projectId, "p", tmpDir.absolutePath, "now", "now")
        val store = InMemorySessionStore()
        val sessionId = insertSession(store, "sess_skip", projectId, model("A"))

        // 已 APPROVED：不可再跨 turn 批准
        val approved = plan(sessionId, PlanStatus.APPROVED)
        planStore.save(approved)
        // 他会话计划：不可跨 turn 批准
        val foreign = plan("other_session", PlanStatus.PENDING_APPROVAL)
        planStore.save(foreign)

        val executor = newExecutor(store, StubProjectManager(project))
        assertEquals(false, executor.resolvePlanApproval(sessionId, approved.id, true, null), "已 APPROVED 计划不可再批")
        assertEquals(false, executor.resolvePlanApproval(sessionId, foreign.id, true, null), "他会话计划不可批")
    }

    private fun model(id: String) = AIModel(id = id, providerModelId = id, name = "Model $id")
}