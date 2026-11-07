package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import xyz.emuci.inkcompose.SelectionMenuAction
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SelectionMenuRenderingTest {
    @Test
    fun custom_array_renders_only_requested_items_and_disabled_click_does_not_execute() = runComposeUiTest {
        val toolbar = PopupTextToolbar()
        var disabledCalls = 0
        var selected: String? = null
        toolbar.showMenu(Rect(40f, 40f, 40f, 40f), {}, {}, {}, {})
        setContent {
            MenuFixture(
                toolbar,
                listOf(
                    SelectionMenuAction.Custom("Disabled action", { disabledCalls++ }, enabled = false),
                    SelectionMenuAction.Custom("Use selection", { selected = it }),
                ),
            )
        }
        onAllNodes(hasClickAction()).assertCountEquals(2)
        onNodeWithText("Disabled action").assertIsDisplayed().assertIsNotEnabled()
            .performTouchInput { click() }
        runOnIdle {
            assertEquals(0, disabledCalls)
            assertEquals(TextToolbarStatus.Shown, toolbar.status)
        }
        onNodeWithText("Use selection").assertIsEnabled().performClick()
        runOnIdle {
            assertEquals("selected text", selected)
            assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        }
        onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun empty_array_renders_no_menu_even_when_platform_callbacks_exist() = runComposeUiTest {
        val toolbar = PopupTextToolbar()
        toolbar.showMenu(Rect(40f, 40f, 40f, 40f), {}, {}, {}, {})
        setContent { MenuFixture(toolbar, emptyList()) }
        onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun default_menu_disables_paste_without_target_and_copy_click_executes_then_dismisses() = runComposeUiTest {
        val toolbar = PopupTextToolbar()
        var copies = 0
        toolbar.showMenu(Rect(40f, 40f, 40f, 40f), { copies++ }, null, {}, {})
        setContent { MenuFixture(toolbar) }
        // The default action order is Copy then Paste, independent of when labels load.
        val actions = onAllNodes(hasClickAction()).assertCountEquals(2)
        actions[0].assertIsDisplayed().assertIsEnabled()
        actions[1].assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        runOnIdle { assertEquals(TextToolbarStatus.Shown, toolbar.status) }
        actions[0].performClick()
        runOnIdle {
            assertEquals(1, copies)
            assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        }
        onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun default_menu_paste_with_target_executes_only_paste_and_dismisses() = runComposeUiTest {
        val toolbar = PopupTextToolbar()
        var copies = 0
        var pastes = 0
        toolbar.showMenu(Rect(40f, 40f, 40f, 40f), { copies++ }, { pastes++ }, {}, {})
        setContent { MenuFixture(toolbar) }
        val actions = onAllNodes(hasClickAction()).assertCountEquals(2)
        actions[1].assertIsDisplayed().assertIsEnabled().performClick()
        runOnIdle {
            assertEquals(0, copies)
            assertEquals(1, pastes)
            assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        }
        onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}

@Composable
private fun MenuFixture(
    toolbar: PopupTextToolbar,
    actions: List<SelectionMenuAction> = listOf(SelectionMenuAction.Copy, SelectionMenuAction.Paste),
) {
    MaterialTheme {
        val controller = rememberMarkdownSelectionController(rememberCoroutineScope(), rememberTextMeasurer())
        LaunchedEffect(controller) {
            controller.updateIndex(listOf(inlineTextBlock(1, "selected text")))
            controller.state.range = SelectionRange(SelectionAnchor(1, 0), SelectionAnchor(1, 13))
            controller.state.isMouseInput = true
        }
        Box(Modifier.size(500.dp)) { PopupTextToolbarHost(toolbar, controller, actions) }
    }
}
