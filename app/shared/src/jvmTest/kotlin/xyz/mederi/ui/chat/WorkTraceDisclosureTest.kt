package xyz.mederi.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.worktrace_has_failure
import mederi.app.shared.generated.resources.worktrace_waiting_response
import xyz.mederi.ui.WorkTraceActivity
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.components.ReasoningBlock
import xyz.mederi.ui.components.ToolCallsBlock
import xyz.mederi.ui.components.WorkTraceCard
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class WorkTraceDisclosureTest {
    @Test
    fun waitingWithoutAssistantContentIsVisibleAndHasNoEmptyDisclosure() = runComposeUiTest {
        var label = ""
        setContent {
            label = stringResource(Res.string.worktrace_waiting_response)
            Box(Modifier.size(700.dp, 300.dp)) {
                WorkTraceCard(ChatListItem.WorkTraceBlock(
                    key = "waiting", items = emptyList(), totalToolsCount = 0,
                    hasFailedTool = false, isRunning = true,
                    activity = WorkTraceActivity.WaitingResponse,
                )) { error("Waiting row has no steps") }
            }
        }
        onNodeWithText(label).assertIsDisplayed()
        onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun workStartsExpandedAndManualCollapseSurvivesCompletionWithFailedTools() = runComposeUiTest {
        val running = mutableStateOf(true)
        var failureLabel = ""
        setContent {
            failureLabel = stringResource(Res.string.worktrace_has_failure)
            Box(Modifier.size(700.dp, 600.dp)) {
                WorkTraceCard(ChatListItem.WorkTraceBlock(
                    key = "work", items = listOf(ChatListItem.Reasoning("step", "reasoning", false)),
                    totalToolsCount = 1, hasFailedTool = true, isRunning = running.value,
                )) { Text("visible intermediate message") }
            }
        }
        onNodeWithText("visible intermediate message").assertIsDisplayed()
        onAllNodesWithText(failureLabel, substring = true).assertCountEquals(0)
        onNode(hasClickAction()).performClick()
        onNodeWithText("visible intermediate message").assertDoesNotExist()
        runOnIdle { running.value = false }
        onNodeWithText("visible intermediate message").assertDoesNotExist()
        onAllNodesWithText(failureLabel, substring = true).assertCountEquals(0)
        onNode(hasClickAction()).performClick()
        onNodeWithText("visible intermediate message").assertIsDisplayed()
    }

    @Test
    fun reasoningStartsCollapsedWithLatestPreviewAndKeepsFullTextForExpansion() = runComposeUiTest {
        val text = "earlier reasoning ".repeat(30) + "latest observation"
        setContent {
            Box(Modifier.size(700.dp, 600.dp)) {
                ReasoningBlock(text = text, contentKey = "reason")
            }
        }
        onNodeWithText(reasoningPreview(text)).assertIsDisplayed()
        onNodeWithText(text).assertDoesNotExist()
        onNode(hasClickAction()).performClick()
        onNodeWithText(reasoningPreview(text)).assertDoesNotExist()
        // 全文渲染器的语义文本包含开头，不能把预览窗口当成完整推理。
        onAllNodesWithText("earlier reasoning", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun everyCommandStartsAsItsOwnSummaryAndFailedOutputStaysInspectable() = runComposeUiTest {
        setContent {
            Box(Modifier.size(700.dp, 600.dp)) {
                ToolCallsBlock(listOf(
                    ToolCallUi("one", "bash", ToolCallState.Completed(output = "first output"), "git branch --show-current"),
                    ToolCallUi("two", "bash", ToolCallState.Failed(error = "bad arguments"), "git invalid"),
                ))
            }
        }
        onAllNodes(hasClickAction()).assertCountEquals(2)
        onNodeWithText("git branch --show-current", substring = true).assertIsDisplayed()
        onNodeWithText("git invalid", substring = true).assertIsDisplayed()
        onNodeWithText("first output").assertDoesNotExist()
        onNodeWithText("bad arguments").assertDoesNotExist()
        onNodeWithText("git invalid", substring = true).performClick()
        onNodeWithText("bad arguments").assertIsDisplayed()
    }
}
