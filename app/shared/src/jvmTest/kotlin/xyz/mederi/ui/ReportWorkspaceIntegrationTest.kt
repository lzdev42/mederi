package xyz.mederi.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReportWorkspaceIntegrationTest {
    @Test fun reportRoutingAndTabPanelActionsPreserveExplicitDocumentSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val core = object : AiCore by MockAiCore() {
            override fun observeConversation(conversationId: String) = emptyFlow<xyz.mederi.core.contract.dto.ConversationSnapshot>()
        }
        val app = AppState(core, InMemoryPreferencesStore(), backgroundScope)
        val store = ViewModelStore(); val vm = WorkspaceViewModel(app); store.put("report", vm)
        try {
            runCurrent()
            val key = ReportKey("a", "event")
            val request = ReportRequest(key, "report.md", "summary")
            vm.openDocument(WorkspaceViewModel.DocumentTarget.Report("report", "Report", request))
            assertIs<ArtifactItem.Report>(vm.artifactItems.single())
            assertEquals(ReportLoadState.Missing, vm.reportState(key))
            assertEquals(RightDockPanel.ARTIFACTS, vm.activeDockPanel)
            vm.openDocument(WorkspaceViewModel.DocumentTarget.Content("text", "Text", "body"))
            assertEquals("text", vm.activeArtifactId)
            vm.retryReport(key); assertEquals("text", vm.activeArtifactId)
            vm.openArtifact("report"); assertEquals("report", vm.activeArtifactId)
            vm.toggleDockPanel(RightDockPanel.ARTIFACTS); assertNull(vm.activeDockPanel)
            vm.openArtifact("text"); assertNull(vm.activeDockPanel)
            vm.openArtifact("report"); assertNull(vm.activeDockPanel)
            assertEquals(ReportLoadState.Missing, vm.reportState(key))
            vm.attach("a")
            val event = ChatListItem.EventMessageCard("event", "background_task_completed", "task", "COMPLETED",
                "", null, null, "command", "raw command output\n")
            vm.openEventReport(event, "Command")
            val details = vm.artifactItems.last() as ArtifactItem.Text
            assertEquals(event.summary, details.content)
            vm.openEventReport(event.copy(eventType = "subagent_completed", reportPath = "report.md"), "Agent report")
            assertEquals(ReportKey("a", "event"), (vm.artifactItems.last() as ArtifactItem.Report).request.key)
            vm.attach("b")
            vm.openEventReport(event.copy(eventType = "subagent_completed", reportPath = "report.md"), "Agent report")
            assertEquals(ReportKey("b", "event"), (vm.artifactItems.last() as ArtifactItem.Report).request.key)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
