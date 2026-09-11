package xyz.mederi.session

import kotlinx.coroutines.runBlocking
import xyz.mederi.api.AgentConfig
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.WorkType
import xyz.mederi.project.ProjectManagerImpl
import xyz.mederi.store.InMemoryDiffStore
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemoryProjectStore
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.store.InMemoryProviderStore
import xyz.mederi.store.InMemoryApiKeyStore
import xyz.mederi.provider.ProviderManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionAndProjectCascadeDeleteTest {

    @Test
    fun testSessionManagerDeleteRemovesDiffAndHistory() = runBlocking {
        val projectStore = InMemoryProjectStore()
        val sessionStore = InMemorySessionStore()
        val historyStore = InMemoryHistoryStore()
        val diffStore = InMemoryDiffStore()
        val providerStore = InMemoryProviderStore()
        val apiKeyStore = InMemoryApiKeyStore()
        val providerManager = ProviderManagerImpl(providerStore, apiKeyStore)
        val projectManager = ProjectManagerImpl(projectStore, sessionStore, historyStore, diffStore)

        val sessionManager = SessionManagerImpl(
            sessionStore = sessionStore,
            historyStore = historyStore,
            projectManager = projectManager,
            providerManager = providerManager,
            diffStore = diffStore,
        )

        val project = projectManager.create("TestProj", listOf("/tmp/test-proj"))
        val session = sessionManager.create(
            agentConfig = AgentConfig(agentMode = AgentMode.AUTONOMOUS, workType = WorkType.CODE),
            projectId = project.id,
            title = "Test Session",
            env = emptyMap(),
        )

        assertNotNull(sessionStore.get(session.id))

        // 删除会话
        sessionManager.delete(session.id)

        assertNull(sessionStore.get(session.id))
        assertEquals(emptyList(), historyStore.load(session.id))
        assertNull(diffStore.get(session.id, null))
    }

    @Test
    fun testProjectManagerDeleteCascadesSessionAndDiff() = runBlocking {
        val projectStore = InMemoryProjectStore()
        val sessionStore = InMemorySessionStore()
        val historyStore = InMemoryHistoryStore()
        val diffStore = InMemoryDiffStore()
        val providerStore = InMemoryProviderStore()
        val apiKeyStore = InMemoryApiKeyStore()
        val providerManager = ProviderManagerImpl(providerStore, apiKeyStore)
        val projectManager = ProjectManagerImpl(projectStore, sessionStore, historyStore, diffStore)

        val sessionManager = SessionManagerImpl(
            sessionStore = sessionStore,
            historyStore = historyStore,
            projectManager = projectManager,
            providerManager = providerManager,
            diffStore = diffStore,
        )

        val project = projectManager.create("TestProj", listOf("/tmp/test-proj"))
        val s1 = sessionManager.create(
            agentConfig = AgentConfig(agentMode = AgentMode.AUTONOMOUS, workType = WorkType.CODE),
            projectId = project.id,
            title = "Session 1",
            env = emptyMap(),
        )
        val s2 = sessionManager.create(
            agentConfig = AgentConfig(agentMode = AgentMode.AUTONOMOUS, workType = WorkType.CODE),
            projectId = project.id,
            title = "Session 2",
            env = emptyMap(),
        )

        assertNotNull(sessionStore.get(s1.id))
        assertNotNull(sessionStore.get(s2.id))

        // 级联删除项目
        projectManager.delete(project.id)

        assertNull(projectStore.get(project.id))
        assertNull(sessionStore.get(s1.id))
        assertNull(sessionStore.get(s2.id))
        assertNull(diffStore.get(s1.id, null))
        assertNull(diffStore.get(s2.id, null))
    }
}
