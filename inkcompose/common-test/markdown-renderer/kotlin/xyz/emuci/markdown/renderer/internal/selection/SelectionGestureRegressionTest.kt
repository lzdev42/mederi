package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import xyz.emuci.inkcompose.MarkdownView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SelectionGestureRegressionTest {
    @Composable
    private fun Fixture(clipboard: Clipboard? = null, bind: (MarkdownSelectionController) -> Unit) {
        val controller = rememberMarkdownSelectionController(rememberCoroutineScope(), rememberTextMeasurer())
        bind(controller)
        SideEffect {
            controller.updateIndex(listOf(inlineTextBlock(1, "alpha beta gamma", width = 300f)))
            clipboard?.let(controller::bindClipboard)
        }
        Box(Modifier.size(300.dp, 60.dp)
            .testTag("reader")
            .onGloballyPositioned {
                controller.registry.setRoot(it)
                controller.registry.register(1, it)
            }
            .markdownSelectionGestures(controller)) {
            SelectionHandlesHost(controller)
        }
    }

    @Test
    fun mouse_drag_starts_at_down_including_movement_inside_touch_slop() = runComposeUiTest {
        lateinit var controller: MarkdownSelectionController
        setContent { Fixture { controller = it } }
        onNodeWithTag("reader").performMouseInput {
            moveTo(Offset(0f, 10f))
            press()
            moveTo(Offset(70f, 10f))
            release()
        }
        runOnIdle {
            assertEquals(0, controller.state.range?.start?.charInBlock)
            assertTrue(controller.selectedText.startsWith("alpha"))
            assertTrue(controller.state.isMouseInput)
            assertEquals(0, controller.state.toolbarRequestKey)
        }
    }

    @Test
    fun ordinary_touch_swipe_does_not_select_text_even_without_a_scroll_parent() = runComposeUiTest {
        lateinit var controller: MarkdownSelectionController
        setContent { Fixture { controller = it } }
        onNodeWithTag("reader").performTouchInput {
            swipe(Offset(10f, 10f), Offset(150f, 10f), 200)
        }
        runOnIdle { assertFalse(controller.hasSelection) }
    }

    @Test
    fun touch_long_press_selects_word_and_next_tap_clears() = runComposeUiTest {
        lateinit var controller: MarkdownSelectionController
        setContent { Fixture { controller = it } }
        onNodeWithTag("reader").performTouchInput {
            down(Offset(10f, 10f))
            advanceEventTime(700)
            moveTo(Offset(10f, 10f))
            up()
        }
        runOnIdle {
            assertEquals("alpha", controller.selectedText)
            assertTrue(controller.state.toolbarRequestKey > 0)
        }
        onNodeWithTag("reader").performTouchInput {
            down(Offset(250f, 40f))
            up()
        }
        runOnIdle { assertFalse(controller.hasSelection) }
    }

    @Test
    fun shift_click_extends_from_previous_collapsed_mouse_caret() = runComposeUiTest {
        lateinit var controller: MarkdownSelectionController
        setContent { Fixture { controller = it } }
        onNodeWithTag("reader").performMouseInput {
            moveTo(Offset(0f, 10f))
            press()
            release()
        }
        runOnIdle {
            assertFalse(controller.hasSelection)
            controller.extendSelectionFromClick(Offset(70f, 10f))
            controller.finishSelectionGesture()
            assertEquals(0, controller.state.range?.start?.charInBlock)
            assertTrue(controller.selectedText.startsWith("alpha"))
        }
    }

    @Test
    fun select_all_includes_all_indexed_blocks() = runComposeUiTest {
        lateinit var controller: MarkdownSelectionController
        setContent { Fixture { controller = it } }
        runOnIdle {
            controller.updateIndex(listOf(inlineTextBlock(1, "first"), inlineTextBlock(2, "second")))
            controller.selectAll()
            assertEquals("first\nsecond", controller.selectedText)
        }
    }
    @Test
    fun normal_link_click_still_opens_link_in_selectable_markdown() = runComposeUiTest {
        var opened: String? = null
        setContent {
            MarkdownView("[Visit example](https://example.com)",
                modifier = Modifier.size(300.dp, 100.dp),
                enableScrollOverride = false,
                onLinkClick = { opened = it })
        }
        waitUntil { onAllNodesWithText("Visit example").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Visit example").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        runOnIdle { assertEquals("https://example.com", opened) }
    }

    @Test
    fun ctrl_a_and_ctrl_c_reach_controller_and_preserve_selection() = runComposeUiTest {
        lateinit var controller: MarkdownSelectionController
        var copied: ClipEntry? = null
        val clipboard = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = copied
            override suspend fun setClipEntry(clipEntry: ClipEntry?) { copied = clipEntry }
        }
        setContent { Fixture(clipboard) { controller = it } }
        onNodeWithTag("reader").performMouseInput {
            moveTo(Offset(0f, 10f)); press(); release()
        }
        onNodeWithTag("reader").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            pressKey(Key.C)
            keyUp(Key.CtrlLeft)
        }
        runOnIdle {
            assertEquals("alpha beta gamma", controller.selectedText)
            assertTrue(copied != null)
        }
    }

    @Test
    fun dismissing_toolbar_keeps_selection_and_escape_clears_it() = runComposeUiTest {
        lateinit var controller: MarkdownSelectionController
        val toolbar = PopupTextToolbar()
        setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                Fixture { controller = it }
                SelectionToolbarHost(controller)
            }
        }
        onNodeWithTag("reader").performMouseInput {
            moveTo(Offset(0f, 10f)); press(); moveTo(Offset(70f, 10f)); release()
        }
        runOnIdle { controller.showContextMenuAt(Offset(20f, 10f)) }
        runOnIdle {
            assertEquals(TextToolbarStatus.Shown, toolbar.status)
            toolbar.hide()
        }
        runOnIdle { assertTrue(controller.hasSelection) }
        onNodeWithTag("reader").performKeyInput { pressKey(Key.Escape) }
        runOnIdle {
            assertFalse(controller.hasSelection)
            assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        }
    }

    @Test
    fun dragging_from_link_selects_text_without_opening_link() = runComposeUiTest {
        var opened = false
        var copied: ClipEntry? = null
        val clipboard = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = copied
            override suspend fun setClipEntry(clipEntry: ClipEntry?) { copied = clipEntry }
        }
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MarkdownView("[Visit example website](https://example.com)",
                    modifier = Modifier.size(300.dp, 100.dp), enableScrollOverride = false,
                    onLinkClick = { opened = true })
            }
        }
        waitUntil { onAllNodesWithText("Visit example website").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Visit example website").performMouseInput {
            moveTo(Offset(2f, 5f)); press(); moveTo(Offset(90f, 5f)); release()
        }
        onNodeWithText("Visit example website").performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft)
        }
        runOnIdle {
            assertFalse(opened)
            assertTrue(copied != null)
        }
    }

    @Test
    fun double_click_drag_expands_whole_words_and_keeps_initial_word() = runComposeUiTest {
        lateinit var controller: MarkdownSelectionController
        setContent { Fixture { controller = it } }
        onNodeWithTag("reader").performMouseInput {
            moveTo(Offset(10f, 10f)); press(); release()
            advanceEventTime(80)
            press()
            moveTo(Offset(60f, 10f))
            release()
        }
        runOnIdle {
            assertEquals("alpha beta", controller.selectedText)
            assertEquals(null, controller.state.dragPointerInWindow)
        }
    }

    @Test
    fun long_press_drag_to_left_keeps_the_initial_word_end() = runComposeUiTest {
        lateinit var controller: MarkdownSelectionController
        setContent { Fixture { controller = it } }
        onNodeWithTag("reader").performTouchInput {
            down(Offset(60f, 10f))
            advanceEventTime(700)
            moveTo(Offset(60f, 10f))
            moveTo(Offset(5f, 10f))
            up()
        }
        runOnIdle {
            assertEquals("alpha beta", controller.selectedText)
            assertEquals(null, controller.state.dragPointerInWindow)
        }
    }

    @Test
    fun mouse_selection_does_not_create_touch_handle_popups() = runComposeUiTest {
        lateinit var controller: MarkdownSelectionController
        setContent { Fixture { controller = it } }
        onNodeWithTag("reader").performMouseInput {
            moveTo(Offset(0f, 10f)); press(); moveTo(Offset(70f, 10f)); release()
        }
        runOnIdle { assertTrue(controller.hasSelection) }
        onNodeWithContentDescription(SelectionStartHandleDescription).assertDoesNotExist()
        onNodeWithContentDescription(SelectionEndHandleDescription).assertDoesNotExist()
        onNodeWithTag("reader").performMouseInput {
            moveTo(Offset(70f, 10f)); press(); moveTo(Offset(0f, 10f)); release()
        }
        runOnIdle { assertTrue(controller.selectedText.startsWith("alpha")) }
    }

    @Test
    fun same_row_columns_hit_the_column_under_the_mouse() = runComposeUiTest {
        lateinit var controller: MarkdownSelectionController
        setContent {
            val current = rememberMarkdownSelectionController(rememberCoroutineScope(), rememberTextMeasurer())
            controller = current
            SideEffect { current.updateIndex(listOf(
                inlineTextBlock(1, "left column", width = 150f),
                inlineTextBlock(2, "right column contents", width = 150f),
            )) }
            Row(Modifier.size(300.dp, 60.dp).testTag("columns")
                .onGloballyPositioned(current.registry::setRoot)
                .markdownSelectionGestures(current)) {
                Box(Modifier.size(150.dp, 60.dp).onGloballyPositioned { current.registry.register(1, it) })
                Box(Modifier.size(150.dp, 60.dp).onGloballyPositioned { current.registry.register(2, it) })
            }
        }
        onNodeWithTag("columns").performMouseInput {
            moveTo(Offset(151f, 10f)); press(); moveTo(Offset(240f, 10f)); release()
        }
        runOnIdle {
            assertEquals(2L, controller.state.range?.start?.blockStableId)
            assertTrue(controller.selectedText.startsWith("right"))
        }
    }

}
