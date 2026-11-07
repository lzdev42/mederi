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
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.SelectionMenuAction
import xyz.emuci.inkcompose.resources.Res
import xyz.emuci.inkcompose.resources.selection_copy
import xyz.emuci.inkcompose.resources.selection_paste
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
        var copyLabel = ""
        var pasteLabel = ""
        toolbar.showMenu(Rect(40f, 40f, 40f, 40f), { copies++ }, null, {}, {})
        setContent {
            MenuFixture(toolbar) { copy, paste -> copyLabel = copy; pasteLabel = paste }
        }
        awaitSelectionTestContent("localized copy and paste labels") {
            copyLabel.isNotBlank() && pasteLabel.isNotBlank()
        }
        onAllNodes(hasClickAction()).assertCountEquals(2)
        onNodeWithText(copyLabel).assertIsDisplayed().assertIsEnabled()
        onNodeWithText(pasteLabel).assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        runOnIdle { assertEquals(TextToolbarStatus.Shown, toolbar.status) }
        onNodeWithText(copyLabel).performClick()
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
        var pasteLabel = ""
        toolbar.showMenu(Rect(40f, 40f, 40f, 40f), { copies++ }, { pastes++ }, {}, {})
        setContent { MenuFixture(toolbar) { _, paste -> pasteLabel = paste } }
        awaitSelectionTestContent("localized paste label") { pasteLabel.isNotBlank() }
        onAllNodes(hasClickAction()).assertCountEquals(2)
        onNodeWithText(pasteLabel).assertIsEnabled().performClick()
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
    onLabels: (String, String) -> Unit = { _, _ -> },
) {
    MaterialTheme {
        val controller = rememberMarkdownSelectionController(rememberCoroutineScope(), rememberTextMeasurer())
        LaunchedEffect(controller) {
            controller.updateIndex(listOf(inlineTextBlock(1, "selected text")))
            controller.state.range = SelectionRange(SelectionAnchor(1, 0), SelectionAnchor(1, 13))
            controller.state.isMouseInput = true
        }
        onLabels(stringResource(Res.string.selection_copy), stringResource(Res.string.selection_paste))
        Box(Modifier.size(500.dp)) { PopupTextToolbarHost(toolbar, controller, actions) }
    }
}
