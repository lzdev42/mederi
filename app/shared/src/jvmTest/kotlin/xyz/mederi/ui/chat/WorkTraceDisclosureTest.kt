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
import mederi.app.shared.generated.resources.worktrace_detail_commands
import mederi.app.shared.generated.resources.worktrace_has_failure
import mederi.app.shared.generated.resources.worktrace_waiting_response
import xyz.mederi.ui.WorkTraceActivity
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
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

    @Test
    fun progressStaysVisibleWhileGroupedCallsAndTheirOutputExpandSeparately() = runComposeUiTest {
        val progress = ChatListItem.TextMessage(
            key = "progress", isUser = false, isStreaming = false, isActiveAssistant = true,
            text = "Lifecycle tests passed", partId = "step", conversationId = "conversation",
        )
        val commands = listOf(
            ToolCallUi("one", "bash", ToolCallState.Completed(output = "first output"), "git status --short"),
            ToolCallUi("two", "bash", ToolCallState.Completed(output = "second output"), "git diff --stat"),
        )
        val steps = commands.map { call -> ChatListItem.ToolCalls(call.id, listOf(call), isStreaming = false) }
        var groupLabel = ""
        setContent {
            groupLabel = pluralStringResource(Res.plurals.worktrace_detail_commands, 2, 2)
            Box(Modifier.size(700.dp, 600.dp)) {
                WorkTraceCard(ChatListItem.WorkTraceBlock(
                    key = "work", items = listOf(progress) + steps,
                    totalToolsCount = 2, hasFailedTool = false,
                )) { item ->
                    when (item) {
                        is ChatListItem.TextMessage -> Text(item.text)
                        is ChatListItem.ToolCalls -> ToolCallsBlock(item.toolCalls)
                        else -> error("Unexpected step: $item")
                    }
                }
            }
        }

        onNodeWithText(progress.text).assertIsDisplayed()
        onNodeWithText("git status --short", substring = true).assertDoesNotExist()
        onNodeWithText(groupLabel).performClick()
        onNodeWithText("git status --short", substring = true).assertIsDisplayed()
        onNodeWithText("git diff --stat", substring = true).assertIsDisplayed()
        onNodeWithText("second output").assertDoesNotExist()
        onNodeWithText("git diff --stat", substring = true).performClick()
        onNodeWithText("second output").assertIsDisplayed()
        onNodeWithText(groupLabel).performClick()
        onNodeWithText("second output").assertDoesNotExist()
        onNodeWithText(progress.text).assertIsDisplayed()
    }
}
