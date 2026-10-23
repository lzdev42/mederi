package xyz.mederi.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState

class SubagentPanelViewModelTest {

    private fun makeSubagent(agentId: String = "sub_1") = SubagentState(
        agentId = agentId,
        parentSessionId = "conv_101",
        role = "EXECUTOR",
        modelId = "m1",
        modelName = "ModelX",
        reasoningLevel = "HIGH",
        task = "do work",
        briefing = null,
        status = "RUNNING",
        startedAt = "2026-10-21T08:00:00Z",
    )

    private fun waitUntilAttached(vm: WorkspaceViewModel, convId: String) = runBlocking {
        val start = xyz.mederi.currentTimeMillis()
        while (vm.conversationId != convId || vm.conversationState == null) {
            if (xyz.mederi.currentTimeMillis() - start > 5000) error("Timed out waiting for attach($convId)")
            delay(20)
        }
    }

    @Test
    fun openSubagentPanel_sets_agentId_and_closes_dock() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val appState = AppState(aiCore = MockAiCore().also { it.initialize() }, preferences = InMemoryPreferencesStore(), scope = scope)
            val vm = WorkspaceViewModel(appState)
            vm.selectConversation("conv_101")
            waitUntilAttached(vm, "conv_101")

            // 先开 Dock
            vm.openDockPanel(RightDockPanel.ARTIFACTS)
            assertEquals(RightDockPanel.ARTIFACTS, vm.activeDockPanel)

            // 开子代理面板 → Dock 应关、agentId 应设
            vm.openSubagentPanel(makeSubagent("sub_1"))
            assertEquals("sub_1", vm.conversationState?.activeSubagentPanelAgentId)
            assertNull(vm.activeDockPanel, "openSubagentPanel should close dock panel")
        } finally { scope.cancel() }
    }

    @Test
    fun openDockPanel_closes_subagent_panel_mutual_exclusion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val appState = AppState(aiCore = MockAiCore().also { it.initialize() }, preferences = InMemoryPreferencesStore(), scope = scope)
            val vm = WorkspaceViewModel(appState)
            vm.selectConversation("conv_101")
            waitUntilAttached(vm, "conv_101")

            // 先开子代理面板
            vm.openSubagentPanel(makeSubagent("sub_2"))
            assertEquals("sub_2", vm.conversationState?.activeSubagentPanelAgentId)

            // 开 Dock → 子代理面板应关
            vm.openDockPanel(RightDockPanel.TERMINAL)
            assertNull(vm.conversationState?.activeSubagentPanelAgentId, "openDockPanel should close subagent panel")
            assertEquals(RightDockPanel.TERMINAL, vm.activeDockPanel)
        } finally { scope.cancel() }
    }

    @Test
    fun closeSubagentPanel_clears_agentId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val appState = AppState(aiCore = MockAiCore().also { it.initialize() }, preferences = InMemoryPreferencesStore(), scope = scope)
            val vm = WorkspaceViewModel(appState)
            vm.selectConversation("conv_101")
            waitUntilAttached(vm, "conv_101")

            vm.openSubagentPanel(makeSubagent("sub_3"))
            vm.closeSubagentPanel()
            assertNull(vm.conversationState?.activeSubagentPanelAgentId)
        } finally { scope.cancel() }
    }
}
