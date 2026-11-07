package xyz.mederi.ui.overview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubagentPanelStateTest {
    @Test
    fun tasksExpandAndFinishCollapsesWithoutUndoingManualHistoryInspection() {
        val state = SubagentPanelState()
        assertFalse(state.expanded)
        state.updateRunning(true)
        assertTrue(state.expanded)
        state.updateRunning(false)
        assertFalse(state.expanded)
        state.expanded = true
        state.updateRunning(false)
        assertTrue(state.expanded)
        assertFalse(SubagentPanelState().expanded, "A different conversation starts collapsed")
    }
}
