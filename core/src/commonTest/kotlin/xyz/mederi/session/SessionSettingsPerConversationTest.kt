package xyz.mederi.session

import kotlinx.coroutines.runBlocking
import xyz.mederi.api.AgentConfig
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.AgentMode.APPROVAL
import xyz.mederi.domain.model.AgentMode.AUTONOMOUS
import xyz.mederi.project.ProjectManagerImpl
import xyz.mederi.provider.ProviderManagerImpl
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.InMemoryApiKeyStore
import xyz.mederi.store.InMemoryDiffStore
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemoryProjectStore
import xyz.mederi.store.InMemoryProviderStore
import xyz.mederi.store.InMemorySessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 验证会话级输入框设置（model / agentMode / reasoningLevel / apiKeyId）按会话独立持久化与读取——
 * 即"切换 A→B→A 各自恢复"的数据层基础。
 *
 * 复现用户测试场景：A、B 两个会话设置不同，切换读取应各自回到自己的值，
 * 不能 A 读出 B 的值（那是全局共享 bug）。
 */
class SessionSettingsPerConversationTest {

    private fun model(id: String) = AIModel(id = id, providerModelId = id, name = "Model $id")

    private data class Ctx(
        val sessionManager: SessionManagerImpl,
        val projectManager: ProjectManagerImpl,
    )

    private fun newCtx(): Ctx {
        val projectStore = InMemoryProjectStore()
        val sessionStore = InMemorySessionStore()
        val historyStore = InMemoryHistoryStore()
        val diffStore = InMemoryDiffStore()
        val providerStore = InMemoryProviderStore()
        val apiKeyStore = InMemoryApiKeyStore()
        val projectManager = ProjectManagerImpl(projectStore, sessionStore, historyStore, diffStore)
        val providerManager = ProviderManagerImpl(providerStore, apiKeyStore)
        val sessionManager = SessionManagerImpl(
            sessionStore = sessionStore,
            historyStore = historyStore,
            projectManager = projectManager,
            providerManager = providerManager,
            diffStore = diffStore,
        )
        return Ctx(sessionManager, projectManager)
    }

    @Test
    fun switchingBetweenConversationsRestoresEachSettings() = runBlocking {
        val (mgr, pm) = newCtx()
        val project = pm.create("Proj", "/tmp/proj")

        // 建两个会话
        val a = mgr.create(
            agentConfig = AgentConfig(agentMode = AUTONOMOUS),
            projectId = project.id, title = "A", env = emptyMap()
        )
        val b = mgr.create(
            agentConfig = AgentConfig(agentMode = AUTONOMOUS),
            projectId = project.id, title = "B", env = emptyMap()
        )

        // A 用 modelX / keyK1 / APPROVAL / HIGH（模拟用户在 A 里选了这些设置并立即持久化）
        mgr.updateAgentConfig(
            id = a.id,
            agentMode = APPROVAL,
            aiModel = model("X"),
            reasoningLevel = ReasoningLevel.HIGH,
            apiKeyId = "K1",
        )
        // B 用 modelY / keyK2 / AUTONOMOUS / LOW
        mgr.updateAgentConfig(
            id = b.id,
            agentMode = AUTONOMOUS,
            aiModel = model("Y"),
            reasoningLevel = ReasoningLevel.LOW,
            apiKeyId = "K2",
        )

        // 读 A → A 的设置
        val readA1 = mgr.require(a.id)
        assertEquals("X", readA1.aiModel?.id, "A modelId should be X after writing A")
        assertEquals("K1", readA1.apiKeyId, "A apiKeyId should be K1")
        assertEquals(APPROVAL, readA1.agentMode, "A agentMode should be APPROVAL")
        assertEquals(ReasoningLevel.HIGH, readA1.reasoningLevel, "A reasoningLevel should be HIGH")

        // 切到 B 读 → B 的设置（不能串台成 A 的）
        val readB = mgr.require(b.id)
        assertEquals("Y", readB.aiModel?.id, "B modelId should be Y")
        assertEquals("K2", readB.apiKeyId, "B apiKeyId should be K2")
        assertEquals(AUTONOMOUS, readB.agentMode, "B agentMode should be AUTONOMOUS")
        assertEquals(ReasoningLevel.LOW, readB.reasoningLevel, "B reasoningLevel should be LOW")
        assertNotEquals(readA1.aiModel?.id, readB.aiModel?.id, "A and B must have distinct models")

        // 再切回 A 读 → 仍是 A 的设置（不被 B 覆盖）
        val readA2 = mgr.require(a.id)
        assertEquals("X", readA2.aiModel?.id, "A modelId must still be X after reading B (per-session isolation)")
        assertEquals("K1", readA2.apiKeyId, "A apiKeyId must still be K1 after reading B")
        assertEquals(APPROVAL, readA2.agentMode, "A agentMode must still be APPROVAL after reading B")
        assertEquals(ReasoningLevel.HIGH, readA2.reasoningLevel, "A reasoningLevel must still be HIGH after reading B")
    }
}
