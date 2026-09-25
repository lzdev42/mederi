package xyz.mederi.koog

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.Project
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.infrastructure.koog.TurnExecutor
import xyz.mederi.project.ProjectManager
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SteeringQueueTest {

    private val tmpDir = File.createTempFile("mederi-steering-test", "").apply {
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

    private fun model(id: String) = AIModel(id = id, providerModelId = id, name = "Model $id")

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun testSteeringQueueEnqueueAndPoll() = runBlocking {
        val store = InMemorySessionStore()
        val historyStore = InMemoryHistoryStore()
        val project = Project("proj_1", "p", tmpDir.absolutePath, "now", "now")
        val executor = TurnExecutor(
            sessionStore = store,
            historyStore = historyStore,
            eventBus = MutableSharedFlow(),
            providerManager = UnusedProviderManager,
            projectManager = StubProjectManager(project)
        )

        val sessionId = "sess_steer_test"
        val now = java.time.Instant.now().toString()
        store.insert(
            Session(
                id = sessionId, projectId = "proj_1", title = "t", status = SessionStatus.RUNNING,
                agentMode = AgentMode.AUTONOMOUS, aiModel = model("m1"),
                reasoningLevel = ReasoningLevel.LOW, env = emptyMap(), createdAt = now, updatedAt = now
            )
        )

        // 1. 处于 RUNNING 状态时发送引导消息
        val request = SendMessageRequest(
            agentConfig = xyz.mederi.api.AgentConfig(AgentMode.AUTONOMOUS, model("m1"), ReasoningLevel.LOW),
            parts = listOf(MessagePart.Text("Please focus on unit tests first"))
        )
        executor.steerMessage(sessionId, request)

        // 2. 轮询提取引导消息
        val polled = executor.pollSteering(sessionId)
        assertNotNull(polled, "应成功提取出入队的引导消息")
        assertEquals("Please focus on unit tests first", polled.text)
        assertEquals(sessionId, polled.sessionId)

        // 3. 再次轮询应为空（单次消费语义）
        val secondPoll = executor.pollSteering(sessionId)
        assertNull(secondPoll, "引导项被消费后再次轮询应为 null")
    }

    @Test
    fun testSteerMessageFallbackWhenSessionNotRunning() = runBlocking {
        val store = InMemorySessionStore()
        val historyStore = InMemoryHistoryStore()
        val project = Project("proj_1", "p", tmpDir.absolutePath, "now", "now")
        val executor = TurnExecutor(
            sessionStore = store,
            historyStore = historyStore,
            eventBus = MutableSharedFlow(),
            providerManager = UnusedProviderManager,
            projectManager = StubProjectManager(project)
        )

        val sessionId = "sess_steer_fallback"
        val now = java.time.Instant.now().toString()
        store.insert(
            Session(
                id = sessionId, projectId = "proj_1", title = "t", status = SessionStatus.IDLE,
                agentMode = AgentMode.AUTONOMOUS, aiModel = model("m1"),
                reasoningLevel = ReasoningLevel.LOW, env = emptyMap(), createdAt = now, updatedAt = now
            )
        )

        val request = SendMessageRequest(
            agentConfig = xyz.mederi.api.AgentConfig(AgentMode.AUTONOMOUS, model("m1"), ReasoningLevel.LOW),
            parts = listOf(MessagePart.Text("Fallback task"))
        )

        // Session 非 RUNNING 状态，steerMessage 应回退到 sendMessage（进而调用 providerManager 触发 UnsupportedOperationException）
        kotlin.test.assertFailsWith<UnsupportedOperationException> {
            executor.steerMessage(sessionId, request)
        }
        assertNull(executor.pollSteering(sessionId), "非 RUNNING 状态下不应入队 steering 队列")
    }
}
