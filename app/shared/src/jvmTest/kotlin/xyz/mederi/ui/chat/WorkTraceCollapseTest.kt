package xyz.mederi.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.WorkTraceActivity
import xyz.mederi.ui.components.WorkTraceCard
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class WorkTraceCollapseTest {
    @Test
    fun deliveryCollapsesOpenWorkAndUserCanReopenWhileWaiting() = runComposeUiTest {
        val trace = mutableStateOf(ChatListItem.WorkTraceBlock(
            key = "work", items = listOf(ChatListItem.Reasoning("r", "details", false)),
            totalToolsCount = 0, hasFailedTool = false, isRunning = true,
            activity = WorkTraceActivity.Thinking,
        ))
        setContent {
            AppTheme {
                WorkTraceCard(trace.value) { Text("process details") }
            }
        }
        onNodeWithText("process details").assertIsDisplayed()
        // 显式手动打开也应在交付阶段切换时收起。
        onNode(hasClickAction()).performClick()
        onNode(hasClickAction()).performClick()
        runOnIdle { trace.value = trace.value.copy(
            defaultExpanded = false, activity = WorkTraceActivity.WaitingSubagents(1)) }
        onNodeWithText("process details").assertDoesNotExist()
        onNode(hasClickAction()).performClick()
        onNodeWithText("process details").assertIsDisplayed()
        // 新工作阶段重新展开，下一次交付仍会再次自动收起。
        runOnIdle { trace.value = trace.value.copy(defaultExpanded = true, activity = WorkTraceActivity.Thinking) }
        onNodeWithText("process details").assertIsDisplayed()
        runOnIdle { trace.value = trace.value.copy(defaultExpanded = false, isRunning = false, activity = null) }
        onNodeWithText("process details").assertDoesNotExist()
    }
}
