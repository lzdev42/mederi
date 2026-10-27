package xyz.mederi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.tool_detail_pending
import mederi.app.shared.generated.resources.tool_detail_running
import mederi.app.shared.generated.resources.tool_detail_empty_output
import mederi.app.shared.generated.resources.tool_detail_empty_error
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.ui.chat.ToolActionGroup
import xyz.mederi.ui.chat.ToolActionKind
import xyz.mederi.ui.components.ReasoningBlock
import xyz.mederi.ui.components.ToolActionGroupRow
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ExpandableDetailsTest {
    @Test
    fun emptyReasoningHasNoClickableDisclosureUntilTextArrives() = runComposeUiTest {
        val text = mutableStateOf("")
        setContent {
            Box(Modifier.size(400.dp)) {
                ReasoningBlock(text = text.value, isReasoningActive = true, contentKey = "reasoning")
            }
        }
        onAllNodes(hasClickAction()).assertCountEquals(0)
        runOnIdle { text.value = "Actual reasoning content" }
        onNode(hasClickAction()).performClick()
        onNodeWithText("Actual reasoning content").assertIsDisplayed()
    }

    @Test
    fun emptyToolResultsExposeInputsAndAccurateLifecycleStatus() = runComposeUiTest {
        val state = mutableStateOf<ToolCallState>(ToolCallState.Pending(mapOf("path" to "docs/example.md")))
        val labels = mutableListOf<String>()
        setContent {
            labels.clear()
            labels.add(stringResource(Res.string.tool_detail_pending))
            labels.add(stringResource(Res.string.tool_detail_running))
            labels.add(stringResource(Res.string.tool_detail_empty_output))
            labels.add(stringResource(Res.string.tool_detail_empty_error))
            Box(Modifier.size(400.dp, 500.dp)) {
                ToolActionGroupRow(
                    group = ToolActionGroup(ToolActionKind.READ, listOf(ToolCallUi("read", "read_file", state.value))),
                    isStreaming = true, isRunning = true,
                )
            }
        }
        onNode(hasClickAction()).performClick()
        onNodeWithText("docs/example.md").assertIsDisplayed()
        onNodeWithText(labels[0]).assertIsDisplayed()
        runOnIdle { state.value = ToolCallState.Running() }
        onNodeWithText(labels[1]).assertIsDisplayed()
        runOnIdle { state.value = ToolCallState.Completed(output = "  \n ") }
        onNodeWithText(labels[2]).assertIsDisplayed()
        runOnIdle { state.value = ToolCallState.Failed(error = "") }
        onNodeWithText(labels[3]).assertIsDisplayed()
        runOnIdle { state.value = ToolCallState.Failed(error = "permission denied") }
        onNodeWithText("permission denied").assertIsDisplayed()
        onAllNodesWithText(labels[3]).assertCountEquals(0)
        runOnIdle { state.value = ToolCallState.Completed(output = "actual file content") }
        onNodeWithText("actual file content").assertIsDisplayed()
        onAllNodesWithText(labels[2]).assertCountEquals(0)
    }

    @Test
    fun runningSubagentCanExposeItsInputBeforeResultArrives() = runComposeUiTest {
        var runningLabel = ""
        setContent {
            runningLabel = stringResource(Res.string.tool_detail_running)
            Box(Modifier.size(400.dp, 500.dp)) {
                ToolActionGroupRow(
                    group = ToolActionGroup(ToolActionKind.SUBAGENT, listOf(ToolCallUi(
                        "agent", "subagent", ToolCallState.Running(mapOf("task" to "Inspect the UI")),
                    ))),
                    isStreaming = true, isRunning = true,
                )
            }
        }
        onNode(hasClickAction()).performClick()
        onNodeWithText("Inspect the UI").assertIsDisplayed()
        onNodeWithText(runningLabel).assertIsDisplayed()
    }
}
