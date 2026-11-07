package xyz.mederi.ui

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.*
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.settings.AgentSettingsViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceSubagentSettingsTest {
    @Test
    fun newConversationSettingsStayInMemoryUntilFirstSend() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val backend = MockAiCore()
        backend.initialize().getOrThrow()
        var creates = 0
        val sent = mutableListOf<String>()
        val core = object : AiCore by backend {
            override suspend fun createConversation(input: CreateConversationInput): Result<Conversation> {
                creates++
                return backend.createConversation(input)
            }
            override suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit> {
                assertEquals("session-key", backend.listSessionSubagentConfigs(conversationId).getOrThrow()
                    .single { it.role == "RESEARCHER" }.apiKeyId)
                assertEquals(5, backend.getSessionSubagentGlobalSettings(conversationId).getOrThrow().maxConcurrentAgents)
                sent += conversationId
                return Result.success(Unit)
            }
        }
        val app = AppState(core, InMemoryPreferencesStore(), backgroundScope)
        app.hydrate()
        val store = ViewModelStore()
        val workspace = WorkspaceViewModel(app).also { store.put("workspace", it) }
        try {
            runCurrent()
            app.selectProject(app.projects.value.first().id)
            app.selectConversation(null)
            runCurrent()
            app.selectModel(app.availableModels.value.first())
            workspace.updateInputDraft(TextFieldValue("keep this draft"))
            val draft = workspace.draftSubagentSettings
            val settings = AgentSettingsViewModel(core, draft = draft).also { store.put("settings", it) }
            runCurrent()
            assertEquals(setOf("EXECUTOR", "RESEARCHER"), settings.uiState.configs.map { it.role }.toSet())
            settings.selectKey("RESEARCHER", "session-key")
            settings.setConcurrency(5)
            runCurrent()
            assertEquals(0, creates)
            assertNull(workspace.conversationId)
            assertTrue(sent.isEmpty())
            assertEquals("keep this draft", workspace.inputDraft.text)
            assertNull(backend.listSubagentConfigs().getOrThrow().single { it.role == "RESEARCHER" }.apiKeyId)
            assertEquals(2, backend.getSubagentGlobalSettings().getOrThrow().maxConcurrentAgents)
            val reopened = AgentSettingsViewModel(core, draft = draft).also { store.put("reopened", it) }
            runCurrent()
            assertEquals("session-key", reopened.uiState.configs.single { it.role == "RESEARCHER" }.apiKeyId)
            workspace.send(workspace.inputDraft.text)
            runCurrent()
            assertEquals(1, creates)
            assertEquals(listOf(workspace.conversationId), sent)
            assertNotSame(draft, workspace.draftSubagentSettings)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
