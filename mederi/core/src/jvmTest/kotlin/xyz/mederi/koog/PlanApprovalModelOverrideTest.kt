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
 * 计划批准携带模型（P2）行为锁定：
 * - 批准 + 带模型 → session 的 aiModel / reasoningLevel 更新为批准时刻的选择
 *   （"最后一次选择"语义；agentMode 不被触碰）
 * - 拒绝（approved=false）或模型为 null → session 保持不变（旧客户端兼容）
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
        override suspend fun get(id: String): Project? = unused()
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
    fun `approval with model overrides session model and reasoning`() = runBlocking {
        val store = InMemorySessionStore()
        val sessionId = insertSession(store)
        val executor = newExecutor(store)

        // 无活跃 requester 也应先完成 session 写入（requester 消费与否不改变"用户已选择"事实）
        executor.resolvePlanApproval(
            sessionId, "plan_x", approved = true,
            aiModel = model("B"), reasoningLevel = ReasoningLevel.HIGH
        )

        val session = store.get(sessionId)!!
        assertEquals("B", session.aiModel?.id, "批准后 session 模型应为 B（批准时刻选择）")
        assertEquals(ReasoningLevel.HIGH, session.reasoningLevel, "批准后推理档位应为 HIGH")
        assertEquals(AgentMode.APPROVAL, session.agentMode, "agentMode 不被触碰")
    }

    @Test
    fun `rejection or null model leaves session unchanged`() = runBlocking {
        val store = InMemorySessionStore()
        val sessionId = insertSession(store)
        val executor = newExecutor(store)

        // 拒绝时即使带模型也不写（拒绝后的修订走新 turn，sendMessage 自带模型）
        executor.resolvePlanApproval(sessionId, "plan_x", approved = false, aiModel = model("B"))
        // 批准但模型为 null（旧客户端）也不写
        executor.resolvePlanApproval(sessionId, "plan_x", approved = true, aiModel = null)

        val session = store.get(sessionId)!!
        assertEquals("A", session.aiModel?.id, "session 模型应保持 A")
        assertEquals(ReasoningLevel.LOW, session.reasoningLevel, "session 推理档位应保持 LOW")
        assertNull(session.reasoningLevel.takeIf { it == ReasoningLevel.HIGH })
    }
}
