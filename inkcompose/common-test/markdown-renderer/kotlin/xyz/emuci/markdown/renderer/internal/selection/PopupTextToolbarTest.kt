package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.emuci.inkcompose.SelectionMenuAction

class PopupTextToolbarTest {
    @Test
    fun typed_copy_and_paste_follow_capability_enabled_state() {
        val state = PopupTextToolbar.MenuState(Rect.Zero, onCopyRequested = {})
        val actions = resolveToolbarActions(state, listOf(SelectionMenuAction.Copy, SelectionMenuAction.Paste), { "selection" }, "copy", "paste")
        assertEquals(listOf("copy", "paste"), actions.map { it.label })
        assertTrue(actions[0].enabled)
        assertFalse(actions[1].enabled)
    }

    @Test
    fun custom_arrays_are_exact_and_empty_array_disables_menu() {
        val state = PopupTextToolbar.MenuState(Rect.Zero, onCopyRequested = {}, onPasteRequested = {})
        assertTrue(resolveToolbarActions(state, emptyList(), { "selection" }, "copy", "paste").isEmpty())
        var received: String? = null
        val actions = resolveToolbarActions(
            state,
            listOf(
                SelectionMenuAction.Custom("custom", { received = it }),
                SelectionMenuAction.Custom("disabled", {}, enabled = false),
            ),
            { "selected text" }, "copy", "paste",
        )
        assertEquals(listOf("custom", "disabled"), actions.map { it.label })
        assertFalse(actions[1].enabled)
        actions[0].onClick()
        assertEquals("selected text", received)
    }

    @Test
    fun typed_actions_keep_order_without_adding_implicit_items() {
        val state = PopupTextToolbar.MenuState(Rect.Zero, onCopyRequested = {}, onPasteRequested = {})
        val actions = resolveToolbarActions(
            state,
            listOf(SelectionMenuAction.Paste, SelectionMenuAction.Custom("custom", {}), SelectionMenuAction.Copy),
            { "selection" }, "copy", "paste",
        )
        assertEquals(listOf("paste", "custom", "copy"), actions.map { it.label })
    }

    @Test
    fun retains_every_supported_action_and_hides_without_invoking_it() {
        val toolbar = PopupTextToolbar()
        val invoked = mutableListOf<String>()
        toolbar.showMenu(
            Rect(100f, 200f, 300f, 250f),
            onCopyRequested = { invoked += "copy" },
            onPasteRequested = { invoked += "paste" },
            onCutRequested = { invoked += "cut" },
            onSelectAllRequested = { invoked += "all" },
        )
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        val state = assertNotNull(toolbar.menuState)
        assertNotNull(state.onCopyRequested).invoke()
        assertNotNull(state.onPasteRequested).invoke()
        assertNotNull(state.onCutRequested).invoke()
        assertNotNull(state.onSelectAllRequested).invoke()
        assertEquals(listOf("copy", "paste", "cut", "all"), invoked)
        toolbar.hide()
        assertNull(toolbar.menuState)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        assertEquals(4, invoked.size)
    }

    @Test
    fun subsequent_menu_replaces_old_callbacks() {
        val toolbar = PopupTextToolbar()
        toolbar.showMenu(Rect.Zero, {}, {}, {}, {})
        toolbar.showMenu(Rect.Zero, null, null, null, null)
        val state = assertNotNull(toolbar.menuState)
        assertNull(state.onCopyRequested)
        assertNull(state.onPasteRequested)
        assertNull(state.onCutRequested)
        assertNull(state.onSelectAllRequested)
    }

    @Test
    fun positions_in_window_coordinates_regardless_of_markdown_container_origin() {
        val provider = SelectionMenuPositionProvider(Rect(300f, 400f, 500f, 420f), true, 8)
        assertEquals(
            IntOffset(300, 428),
            provider.calculatePosition(IntRect(260, 350, 700, 800), IntSize(1000, 900), LayoutDirection.Ltr, IntSize(224, 100)),
        )
    }

    @Test
    fun touch_toolbar_is_centered_and_moves_below_selection_near_top() {
        val provider = SelectionMenuPositionProvider(Rect(200f, 10f, 400f, 30f), false, 8)
        assertEquals(
            IntOffset(180, 38),
            provider.calculatePosition(IntRect.Zero, IntSize(800, 600), LayoutDirection.Ltr, IntSize(240, 120)),
        )
    }

    @Test
    fun menu_stays_inside_all_window_edges_and_handles_tiny_windows() {
        val provider = SelectionMenuPositionProvider(Rect(790f, 580f, 810f, 620f), true, 8)
        assertEquals(
            IntOffset(568, 472),
            provider.calculatePosition(IntRect.Zero, IntSize(800, 600), LayoutDirection.Ltr, IntSize(224, 100)),
        )
        assertEquals(
            IntOffset.Zero,
            provider.calculatePosition(IntRect.Zero, IntSize(100, 50), LayoutDirection.Ltr, IntSize(224, 100)),
        )
    }

    @Test
    fun rtl_mouse_menu_aligns_its_right_edge_with_selection() {
        val provider = SelectionMenuPositionProvider(Rect(300f, 400f, 500f, 420f), true, 8)
        assertEquals(
            IntOffset(276, 428),
            provider.calculatePosition(IntRect.Zero, IntSize(1000, 900), LayoutDirection.Rtl, IntSize(224, 100)),
        )
    }
}
