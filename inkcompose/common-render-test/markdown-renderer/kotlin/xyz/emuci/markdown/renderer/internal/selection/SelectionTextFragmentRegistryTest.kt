package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class SelectionTextFragmentRegistryTest {
    @Test
    fun painted_code_lines_map_to_individual_characters_and_highlight_only_selected_text() = runComposeUiTest {
        val registry = SelectionTextFragmentRegistry()
        val observer = registry.observer(7)
        var rootCoordinates: LayoutCoordinates? = null
        var secondCoordinates: LayoutCoordinates? = null
        var secondLayout: TextLayoutResult? = null
        setContent {
            Column(Modifier.onGloballyPositioned { rootCoordinates = it }) {
                BasicText("first", Modifier.onGloballyPositioned { observer.onPositioned(0, it) },
                    onTextLayout = { observer.onTextLayout(0, it) })
                BasicText("second", Modifier.onGloballyPositioned {
                    secondCoordinates = it
                    observer.onPositioned(6, it)
                }, onTextLayout = {
                    secondLayout = it
                    observer.onTextLayout(6, it)
                })
            }
        }
        waitForIdle()
        runOnIdle {
            val layout = assertNotNull(secondLayout)
            val coordinates = assertNotNull(secondCoordinates)
            val caret = layout.getCursorRect(3)
            val window = coordinates.localToWindow(Offset(caret.left, caret.center.y))
            assertEquals(9, registry.offsetAt(7, window))
            val index = SelectionModelIndex(listOf(SelectionBlockEntry(7, 0, 12, "first\nsecond")))
            val boxes = assertNotNull(registry.highlightBoxes(7,
                SelectionRange(SelectionAnchor(7, 7), SelectionAnchor(7, 10)), index, rootCoordinates))
            val box = boxes.single()
            val expectedStart = requireNotNull(rootCoordinates).windowToLocal(
                coordinates.localToWindow(layout.getBoundingBox(1).topLeft))
            val expectedEnd = requireNotNull(rootCoordinates).windowToLocal(
                coordinates.localToWindow(layout.getBoundingBox(3).bottomRight))
            assertEquals(expectedStart.x, box.left, 0.5f)
            assertEquals(expectedStart.y, box.top, 0.5f)
            assertEquals(expectedEnd.x, box.right, 0.5f)
            assertEquals(expectedEnd.y, box.bottom, 0.5f)
            assertTrue(box.width > 0)
            assertNotNull(registry.positionFor(SelectionAnchor(7, 9)))
            registry.removeBlock(7)
            assertNull(registry.offsetAt(7, window))
        }
    }

    @Test
    fun table_cell_base_offsets_preserve_tab_and_newline_positions() = runComposeUiTest {
        val registry = SelectionTextFragmentRegistry()
        val observer = registry.observer(2, baseOffset = 8)
        var coordinates: LayoutCoordinates? = null
        var layout: TextLayoutResult? = null
        setContent {
            BasicText("value", Modifier.onGloballyPositioned {
                coordinates = it
                observer.onPositioned(0, it)
            }, onTextLayout = { layout = it; observer.onTextLayout(0, it) })
        }
        waitForIdle()
        runOnIdle {
            val cursor = assertNotNull(layout).getCursorRect(2)
            val window = assertNotNull(coordinates).localToWindow(Offset(cursor.left, cursor.center.y))
            assertEquals(10, registry.offsetAt(2, window))
            observer.onDispose(0)
            assertNull(registry.offsetAt(2, window))
        }
    }
}

