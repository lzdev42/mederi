package xyz.mederi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.components.WorkspaceFeedbackContent
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WorkspaceFeedbackDeliveryTest {
    @Test fun coreWarningAndLocalFailureUseSameBoardWithCorrectActions() = runComposeUiTest {
        val state = mutableStateOf(ErrorState(UiMessage(Res.string.err_generic, listOf("core warning")), "diagnostic", "id", true))
        var detail = ""; var resume = ""; var close = ""; var details = 0; var continued = 0; var dismissed = 0
        setContent { AppTheme(reducedMotion = true) {
            detail = stringResource(Res.string.errorboard_detail_report); resume = stringResource(Res.string.errorboard_continue)
            close = stringResource(Res.string.errorboard_close)
            Box(Modifier.width(700.dp).testTag("feedback")) { WorkspaceFeedbackContent(state.value, { details++ }, { dismissed++ }, { continued++ }) }
        } }
        assertTrue(onNodeWithTag("feedback").fetchSemanticsNode().size.height <= with(density) { 40.dp.roundToPx() })
        onNodeWithText("core warning").assertIsDisplayed(); onNodeWithText(detail).performClick(); onNodeWithText(resume).performClick()
        runOnIdle { assertEquals(1, details); assertEquals(1, continued); state.value = ErrorState(UiMessage(Res.string.err_generic, listOf("local failure"))) }
        onNodeWithText("local failure").assertIsDisplayed(); onNodeWithText(resume).assertDoesNotExist()
        onNodeWithContentDescription(close).performClick(); runOnIdle { assertEquals(1, dismissed) }
    }
    @Test fun multilineFailureRemainsCompactWithInlineDetailsAndDismiss() = runComposeUiTest {
        var detail = ""; var close = ""
        setContent { AppTheme(reducedMotion = true) {
            detail = stringResource(Res.string.errorboard_detail_report)
            close = stringResource(Res.string.errorboard_close)
            Box(Modifier.width(700.dp).testTag("feedback")) {
                WorkspaceFeedbackContent(ErrorState(UiMessage(Res.string.err_generic,
                    listOf("Failed to connect\n".repeat(20)))), {}, {}, {})
            }
        } }
        assertTrue(onNodeWithTag("feedback").fetchSemanticsNode().size.height <= with(density) { 40.dp.roundToPx() })
        onNodeWithText(detail).assertIsDisplayed()
        onNodeWithContentDescription(close).assertIsDisplayed()
    }

    @Test fun clearedFeedbackHasNoGhostActionsOrOldContent() = runComposeUiTest {
        val state = mutableStateOf(ErrorState(UiMessage(Res.string.err_generic, listOf("visible"))))
        var detail = ""; var resume = ""
        setContent { AppTheme(reducedMotion = true) {
            detail = stringResource(Res.string.errorboard_detail_report); resume = stringResource(Res.string.errorboard_continue)
            WorkspaceFeedbackContent(state.value, {}, {}, {})
        } }
        onNodeWithText("visible").assertIsDisplayed(); runOnIdle { state.value = ErrorState() }
        onNodeWithText("visible").assertDoesNotExist(); onNodeWithText(detail).assertDoesNotExist(); onNodeWithText(resume).assertDoesNotExist()
    }
}
