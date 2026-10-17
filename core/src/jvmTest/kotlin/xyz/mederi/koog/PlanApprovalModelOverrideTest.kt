package xyz.mederi.koog

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.Project
import xyz.mederi.project.ProjectManager
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.infrastructure.koog.TurnExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 计划批准行为验证：
 * - 计划审批仅批准技术方案，绝不修改会话模型与推理档位（session.aiModel / reasoningLevel 保持不变）
 */
class PlanApprovalModelOverrideTest {

    // ---- 测试用空 fake：resolvePlanApproval 路径不触达 Provider/Project，只满足构造依赖 ----

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

    private object UnusedProjectManager : ProjectManager {
        private fun unused(): Nothing = throw UnsupportedOperationException("not used in this test")
        override suspend fun list(): List<Project> = unused()
        // resolvePlanApproval 跨 turn 分支在 requester 不存活且批准时会访问 projectManager 以构建
        // PlanStore 查找待批准计划；本测试无真实项目，返回 null → 跨 turn 因计划不存在安全 no-op。
        override suspend fun get(id: String): Project? = null
        override suspend fun require(id: String): Project = unused()
        override suspend fun create(name: String, directory: String): Project = unused()
        override suspend fun delete(id: String) = unused()
        override suspend fun rename(id: String, name: String): Project = unused()
    }

    private fun model(id: String) = AIModel(id = id, providerModelId = id, name = "Model $id")

    private fun newExecutor(store: InMemorySessionStore) = TurnExecutor(
        sessionStore = store,
        historyStore = xyz.mederi.store.InMemoryHistoryStore(),
        eventBus = MutableSharedFlow<xyz.mederi.domain.model.MederiEvent>(),
        providerManager = UnusedProviderManager,
        projectManager = UnusedProjectManager
    )

    private suspend fun insertSession(store: InMemorySessionStore): String {
        val sessionId = "sess_${System.nanoTime()}"
        val now = java.time.Instant.now().toString()
        store.insert(Session(
            id = sessionId, projectId = "p1", title = "t", status = SessionStatus.IDLE,
            agentMode = AgentMode.APPROVAL,
            aiModel = model("A"), reasoningLevel = ReasoningLevel.LOW,
            env = emptyMap(), createdAt = now, updatedAt = now
        ))
        return sessionId
    }

    @Test
    fun `approval never modifies session model or reasoning`() = runBlocking {
        val store = InMemorySessionStore()
        val sessionId = insertSession(store)
        val executor = newExecutor(store)

        executor.resolvePlanApproval(
            sessionId, "plan_x", approved = true
        )

        val session = store.get(sessionId)!!
        assertEquals("A", session.aiModel?.id, "批准绝不修改 session 模型，仍为 A")
        assertEquals(ReasoningLevel.LOW, session.reasoningLevel, "批准绝不修改推理档位，仍为 LOW")
        assertEquals(AgentMode.APPROVAL, session.agentMode, "agentMode 保持不变")
    }

    @Test
    fun `rejection leaves session unchanged`() = runBlocking {
        val store = InMemorySessionStore()
        val sessionId = insertSession(store)
        val executor = newExecutor(store)

        executor.resolvePlanApproval(sessionId, "plan_x", approved = false)

        val session = store.get(sessionId)!!
        assertEquals("A", session.aiModel?.id, "session 模型应保持 A")
        assertEquals(ReasoningLevel.LOW, session.reasoningLevel, "session 推理档位应保持 LOW")
    }
}
