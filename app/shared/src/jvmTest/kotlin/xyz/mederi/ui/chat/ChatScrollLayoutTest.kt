package xyz.mederi.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.reasoning_thought
import mederi.app.shared.generated.resources.worktrace_expand
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.components.ReasoningBlock
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatScrollLayoutTest {
    @Test
    fun actualReasoningExpansionAndStreamingDoNotDisplaceBottomControl() = runComposeUiTest {
        val text = mutableStateOf((1..35).joinToString("\n\n") { "Reasoning paragraph $it" })
        val bottoms = mutableListOf<Float>()
        var header = ""
        var expandLabel = ""
        val state = LazyListState()
        setContent {
            header = stringResource(Res.string.reasoning_thought)
            expandLabel = stringResource(Res.string.worktrace_expand)
            ChatScrollLayout(
                items = listOf("history", "reasoning"), state = state,
                modifier = Modifier.size(400.dp, 360.dp), itemKey = { it },
            ) { _, item ->
                if (item == "history") Spacer(Modifier.height(500.dp))
                else ReasoningBlock(
                    text = text.value, isStreaming = true, contentKey = "reasoning",
                    modifier = Modifier.onGloballyPositioned {
                        bottoms.add(it.positionInRoot().y + it.size.height)
                    },
                )
            }
        }
        onNodeWithText(header).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(expandLabel).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val bottom = bottoms.last()
        runOnIdle { bottoms.clear() }
        onNodeWithText(expandLabel).performClick()
        waitForIdle()
        runOnIdle { text.value += "\n\nMore streaming reasoning content" }
        waitForIdle()
        runOnIdle {
            assertTrue(bottoms.isNotEmpty())
            assertTrue(bottoms.all { abs(it - bottom) <= 1f }, "reasoning footer moved: $bottoms")
        }
    }

    @Test
    fun expandingAndStreamingKeepButtonAnchoredOnEveryLayout() = runComposeUiTest {
        val expandedHeight = mutableStateOf(180.dp)
        val state = LazyListState()
        val buttonBottoms = mutableListOf<Float>()
        setContent {
            val height by animateDpAsState(expandedHeight.value, tween(240))
            ChatScrollLayout(
                items = listOf("history", "reasoning"), state = state,
                modifier = Modifier.size(320.dp, 360.dp), itemKey = { it },
            ) { _, item ->
                if (item == "history") Spacer(Modifier.height(500.dp))
                else Column(Modifier.width(300.dp)) {
                    Spacer(Modifier.height(height))
                    Box(Modifier.size(80.dp, 30.dp).onGloballyPositioned {
                        buttonBottoms.add(it.positionInRoot().y + it.size.height)
                    })
                }
            }
        }
        waitForIdle()
        val initialBottom = buttonBottoms.last()
        runOnIdle { buttonBottoms.clear(); expandedHeight.value = 900.dp }
        waitForIdle()
        runOnIdle { expandedHeight.value = 1200.dp }
        waitForIdle()
        runOnIdle {
            assertTrue(buttonBottoms.isNotEmpty())
            assertTrue(buttonBottoms.all { abs(it - initialBottom) <= 1f }, "button moved: $buttonBottoms")
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun newContentPushesReasoningUpAndViewportResizeKeepsTailVisible() = runComposeUiTest {
        val items = mutableStateOf(listOf("history", "reasoning"))
        val viewportHeight = mutableStateOf(360.dp)
        val state = LazyListState()
        var reasoningBottom = 0f
        var replyBottom = 0f
        setContent {
            ChatScrollLayout(
                items = items.value, state = state,
                modifier = Modifier.width(320.dp).height(viewportHeight.value), itemKey = { it },
            ) { _, item ->
                Box(Modifier.width(300.dp).height(if (item == "reply") 80.dp else 500.dp)
                    .onGloballyPositioned {
                        val bottom = it.positionInRoot().y + it.size.height
                        if (item == "reasoning") reasoningBottom = bottom
                        if (item == "reply") replyBottom = bottom
                    })
            }
        }
        waitForIdle()
        val originalBottom = reasoningBottom
        runOnIdle { items.value = items.value + "reply" }
        waitForIdle()
        runOnIdle {
            assertTrue(reasoningBottom < originalBottom)
            assertEquals(originalBottom, replyBottom, 1f)
            viewportHeight.value = 280.dp
        }
        waitForIdle()
        runOnIdle {
            assertTrue(replyBottom < originalBottom)
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun readingHistoryIsPreservedWhenNewStepsArrive() = runComposeUiTest {
        val items = mutableStateOf((1..12).map { "step$it" })
        val state = LazyListState()
        var readingPosition = 0f
        setContent {
            ChatScrollLayout(
                items = items.value, state = state,
                modifier = Modifier.size(320.dp, 360.dp), itemKey = { it },
            ) { _, item ->
                Box(Modifier.size(300.dp, 100.dp).onGloballyPositioned {
                    if (item == "step8") readingPosition = it.positionInRoot().y
                })
            }
        }
        runOnIdle { state.requestScrollToItem(5) }
        waitForIdle()
        val before = readingPosition
        runOnIdle { items.value = items.value + "new step" }
        waitForIdle()
        runOnIdle {
            assertEquals(before, readingPosition, 1f)
            assertTrue(state.firstVisibleItemIndex > 0)
        }
    }

    @Test
    fun shortConversationRemainsInChronologicalOrderAtTop() = runComposeUiTest {
        val positions = mutableMapOf<String, Float>()
        val indices = mutableMapOf<String, Int>()
        setContent {
            ChatScrollLayout(
                items = listOf("user", "reasoning", "reply"), state = LazyListState(),
                modifier = Modifier.size(320.dp, 360.dp), itemKey = { it },
            ) { index, item ->
                indices[item] = index
                Box(Modifier.size(300.dp, 40.dp).onGloballyPositioned {
                    positions[item] = it.positionInRoot().y
                })
            }
        }
        waitForIdle()
        runOnIdle {
            assertEquals(0f, positions.getValue("user"), 1f)
            assertTrue(positions.getValue("user") < positions.getValue("reasoning"))
            assertTrue(positions.getValue("reasoning") < positions.getValue("reply"))
            assertEquals(mapOf("user" to 0, "reasoning" to 1, "reply" to 2), indices)
        }
    }
}
