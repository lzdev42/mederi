package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SelectionAutoScrollTest {
    @Test
    fun edge_velocity_is_directional_gradual_and_capped() {
        assertEquals(0f, selectionAutoScrollVelocity(100f, 0f, 200f, 40f, 1000f))
        assertEquals(-500f, selectionAutoScrollVelocity(20f, 0f, 200f, 40f, 1000f))
        assertEquals(500f, selectionAutoScrollVelocity(180f, 0f, 200f, 40f, 1000f))
        assertEquals(1000f, selectionAutoScrollVelocity(500f, 0f, 200f, 40f, 1000f))
        assertEquals(0f, selectionAutoScrollVelocity(20f, 0f, 0f, 40f, 1000f))
    }

    @Test
    fun internal_list_scrolls_with_stationary_pointer_and_stops_after_release() = runComposeUiTest {
        mainClock.autoAdvance = false
        var scrollOffset: (() -> Int)? = null
        var controllerRef: MarkdownSelectionController? = null
        setContent {
            val controller = rememberMarkdownSelectionController(rememberCoroutineScope(), rememberTextMeasurer())
            val list = rememberLazyListState()
            val dispatcher = remember { NestedScrollDispatcher() }
            val connection = remember { object : NestedScrollConnection {} }
            val pointerInitialized = remember { booleanArrayOf(false) }
            val pointerY = with(LocalDensity.current) { 178.dp.toPx() }
            controllerRef = controller
            scrollOffset = { list.firstVisibleItemScrollOffset }
            Box(
                Modifier.height(180.dp).fillMaxWidth()
                    .nestedScroll(connection, dispatcher)
                    .onGloballyPositioned {
                        controller.registry.setRoot(it)
                        if (!pointerInitialized[0]) {
                            pointerInitialized[0] = true
                            controller.state.dragPointerInWindow = it.localToWindow(Offset(20f, it.size.height - 2f))
                        }
                    },
            ) {
                LazyColumn(state = list) { item { Box(Modifier.height(2000.dp).fillMaxWidth()) } }
                SelectionAutoScrollHost(controller, list, true, dispatcher)
            }
        }
        mainClock.advanceTimeBy(500)
        runOnIdle {
            assertTrue(requireNotNull(scrollOffset).invoke() > 0, "stationary edge pointer must scroll internal list")
            requireNotNull(controllerRef).state.dragPointerInWindow = null
        }
        val stoppedAt = requireNotNull(scrollOffset).invoke()
        mainClock.advanceTimeBy(200)
        runOnIdle { assertEquals(stoppedAt, requireNotNull(scrollOffset).invoke()) }
    }

    @Test
    fun static_document_dispatches_scroll_to_parent_list() = runComposeUiTest {
        mainClock.autoAdvance = false
        var scrollOffset: (() -> Int)? = null
        var controllerRef: MarkdownSelectionController? = null
        setContent {
            val parent = rememberLazyListState()
            val unusedInnerList = rememberLazyListState()
            val controller = rememberMarkdownSelectionController(rememberCoroutineScope(), rememberTextMeasurer())
            val dispatcher = remember { NestedScrollDispatcher() }
            val connection = remember { object : NestedScrollConnection {} }
            val pointerInitialized = remember { booleanArrayOf(false) }
            val pointerY = with(LocalDensity.current) { 178.dp.toPx() }
            controllerRef = controller
            scrollOffset = { parent.firstVisibleItemScrollOffset }
            LazyColumn(Modifier.height(180.dp).fillMaxWidth(), state = parent) {
                item {
                    Box(
                        Modifier.height(2000.dp).fillMaxWidth()
                            .nestedScroll(connection, dispatcher)
                            .onGloballyPositioned {
                                controller.registry.setRoot(it)
                                if (!pointerInitialized[0]) {
                                    pointerInitialized[0] = true
                                    // Fixed window point at the bottom of the parent viewport.
                                    controller.state.dragPointerInWindow = it.localToWindow(Offset(20f, pointerY))
                                }
                            },
                    ) {
                        SelectionAutoScrollHost(controller, unusedInnerList, false, dispatcher)
                    }
                }
            }
        }
        mainClock.advanceTimeBy(500)
        runOnIdle {
            assertTrue(requireNotNull(scrollOffset).invoke() > 0, "static markdown must scroll ancestor list")
            requireNotNull(controllerRef).state.dragPointerInWindow = null
        }
    }
}
