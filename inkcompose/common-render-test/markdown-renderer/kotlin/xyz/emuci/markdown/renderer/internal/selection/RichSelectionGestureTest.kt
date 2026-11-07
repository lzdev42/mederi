package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import xyz.emuci.markdown.renderer.Markdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Exercise the real parser → painter → geometry bridge → gesture → extraction chain. */
@OptIn(ExperimentalTestApi::class)
class RichSelectionGestureTest {
    @Test
    fun mouse_drag_across_paragraph_code_and_table_copies_the_same_text_in_both_directions() = runComposeUiTest {
        var controller: MarkdownSelectionController? = null
        setContent {
            Markdown(
                markdown = "opening\n\n```kotlin\nval code = 42\nsecond line\n```\n\n| head | other |\n| --- | --- |\n| alpha | omega |",
                modifier = Modifier.width(400.dp).testTag("reader"),
                enableScroll = false,
                header = {
                    val selection = LocalMarkdownSelectionController.current
                    SideEffect { controller = selection }
                },
            )
        }
        awaitSelectionTestContent("parsed Markdown and selection controller") { controller != null }
        val readerBounds = onNodeWithTag("reader").fetchSemanticsNode().boundsInRoot
        val first = onNodeWithText("opening").fetchSemanticsNode().boundsInRoot
        val last = onNodeWithText("omega").fetchSemanticsNode().boundsInRoot
        val start = Offset(first.left - readerBounds.left, first.center.y - readerBounds.top)
        val end = Offset(last.right + 1f - readerBounds.left, last.center.y - readerBounds.top)
        onNodeWithTag("reader").performMouseInput {
            moveTo(start)
            press()
            moveTo(end)
            release()
        }
        // Fenced code retains its literal trailing newline. The block separator is
        // additional structural text; copying must not trim the user's code source.
        val expected = listOf("opening", "val code = 42\nsecond line\n", "head\tother\nalpha\tomega")
            .joinToString("\n")
        runOnIdle { assertEquals(expected, assertNotNull(controller).selectedText) }
        onNodeWithTag("reader").performMouseInput {
            moveTo(end)
            press()
            moveTo(start)
            release()
        }
        runOnIdle { assertEquals(expected, assertNotNull(controller).selectedText) }
    }
}
