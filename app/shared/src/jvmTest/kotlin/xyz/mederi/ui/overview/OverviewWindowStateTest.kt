package xyz.mederi.ui.overview

import kotlinx.coroutines.runBlocking
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverviewWindowStateTest {
    @Test
    fun `overview is visible by default including first preference load`() = runBlocking {
        val state = OverviewWindowState()
        assertTrue(state.visible)
        state.load(InMemoryPreferencesStore())
        assertTrue(state.visible)
    }

    @Test
    fun `hiding persists across workspace recreation and can be shown again`() = runBlocking {
        val preferences = InMemoryPreferencesStore()
        OverviewWindowState().apply { visible = false }.save(preferences)
        val restored = OverviewWindowState()
        restored.load(preferences)
        assertFalse(restored.visible)
        restored.visible = true
        restored.save(preferences)
        val shown = OverviewWindowState()
        shown.load(preferences)
        assertTrue(shown.visible)
    }

    @Test
    fun `old minimized preference maps to hidden without losing user preference`() = runBlocking {
        for (minimized in listOf(true, false)) {
            val preferences = InMemoryPreferencesStore()
            preferences.putBoolean("overview.window.minimized", minimized)
            val state = OverviewWindowState()
            state.load(preferences)
            assertEquals(!minimized, state.visible)
        }
    }

    @Test
    fun `new visibility preference wins over old minimized preference`() = runBlocking {
        for (visible in listOf(true, false)) {
            val preferences = InMemoryPreferencesStore()
            // 两个键的语义故意冲突，确认新键优先而非旧键覆盖。
            preferences.putBoolean("overview.window.minimized", visible)
            preferences.putBoolean("overview.window.visible", visible)
            val state = OverviewWindowState()
            state.load(preferences)
            assertEquals(visible, state.visible)
        }
    }

    @Test
    fun `hiding and showing keeps the chosen accordion section`() {
        val state = OverviewWindowState()
        state.expandedRowIndex = 4
        state.visible = false
        assertEquals(4, state.expandedRowIndex)
        state.visible = true
        assertEquals(4, state.expandedRowIndex)
    }
}
