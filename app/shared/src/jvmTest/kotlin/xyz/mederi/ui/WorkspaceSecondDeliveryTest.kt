package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Terminal
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.QuestionRequest
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.theme.AppTheme
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.chat.ToolActionGroup
import xyz.mederi.ui.chat.ToolActionKind
import xyz.mederi.ui.components.ErrorBoard
import xyz.mederi.ui.components.PlanApprovalCard
import xyz.mederi.ui.components.QuestionCard
import xyz.mederi.ui.components.StatusBar
import xyz.mederi.ui.components.ToolActionGroupRow
import xyz.mederi.ui.components.atoms.ProcessRow
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** 主对话交互卡的真实组件回归，fixture只用于测试，不改变Core数据或业务规则。 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceSecondDeliveryTest {
    private val plan = PlanApprovalRequest(
        id = "plan-fixture", conversationId = "session-fixture", planPath = "plan.md",
        title = "Review component consistency", summary = "Keep the current workflows and unify presentation.",
    )

    private fun question(multi: Boolean = false) = QuestionRequest(questions = listOf(
        QuestionRequest.Question(prompt = "Which option should we use?", options = listOf("First", "Second"), multiSelect = multi)
    ))

    @Test
    fun singleChoiceAndCustomAnswerStayControlledByCaller() = runComposeUiTest {
        val answers = mutableStateOf(listOf("Custom answer"))
        setContent {
            AppTheme {
                Box(Modifier.width(360.dp)) {
                    QuestionCard(question = question(), currentIndex = 0,
                        selectedAnswers = answers.value, onAnswer = { answers.value = it })
                }
            }
        }
        onNodeWithText("First").performClick()
        runOnIdle { assertEquals(listOf("First", "Custom answer"), answers.value) }
        onNodeWithText("Second").performClick()
        runOnIdle { assertEquals(listOf("Second", "Custom answer"), answers.value) }
        onNode(hasSetTextAction()).performTextReplacement("Another answer")
        runOnIdle { assertEquals(listOf("Second", "Another answer"), answers.value) }
    }

    @Test
    fun multipleChoiceAndExplicitActionsKeepTheirSeparateCallbacks() = runComposeUiTest {
        val answers = mutableStateOf(emptyList<String>())
        var submitted = 0
        var cancelled = 0
        var submitLabel = ""
        var cancelLabel = ""
        setContent {
            AppTheme {
                submitLabel = stringResource(Res.string.question_submit)
                cancelLabel = stringResource(Res.string.question_cancel)
                Box(Modifier.width(360.dp)) {
                    QuestionCard(question = question(multi = true), currentIndex = 0,
                        selectedAnswers = answers.value, onAnswer = { answers.value = it },
                        onSubmit = { submitted++ }, onCancel = { cancelled++ })
                }
            }
        }
        onNodeWithText("First").performClick()
        onNodeWithText("Second").performClick()
        onNodeWithText("First").performClick()
        runOnIdle { assertEquals(listOf("Second"), answers.value) }
        onNodeWithText(submitLabel).performClick()
        onNodeWithText(cancelLabel).performClick()
        runOnIdle { assertEquals(1, submitted); assertEquals(1, cancelled) }
    }

    @Test
    fun freeTextAndQuestionNavigationKeepOriginalCallbacks() = runComposeUiTest {
        val current = mutableStateOf(0)
        val answers = mutableStateOf(emptyList<String>())
        var next = 0
        var previous = 0
        var nextLabel = ""
        var previousLabel = ""
        val request = QuestionRequest(questions = listOf(
            QuestionRequest.Question(prompt = "Explain the choice"),
            QuestionRequest.Question(prompt = "Confirm the choice"),
        ))
        setContent {
            AppTheme {
                nextLabel = stringResource(Res.string.question_next)
                previousLabel = stringResource(Res.string.question_prev)
                Box(Modifier.width(360.dp)) {
                    QuestionCard(request, current.value, answers.value, { answers.value = it },
                        onNextPage = { next++; current.value = 1 },
                        onPrevPage = { previous++; current.value = 0 })
                }
            }
        }
        onNode(hasSetTextAction()).performTextReplacement("Keep the workflow")
        runOnIdle { assertEquals(listOf("Keep the workflow"), answers.value) }
        onNodeWithText(nextLabel).performClick()
        onNodeWithText("Confirm the choice").assertIsDisplayed()
        onNodeWithText(previousLabel).performClick()
        onNodeWithText("Explain the choice").assertIsDisplayed()
        runOnIdle { assertEquals(1, next); assertEquals(1, previous) }
    }

    @Test
    fun openingAndApprovingPlanDoNotTriggerEachOther() = runComposeUiTest {
        var opens = 0
        var approvals = 0
        var title = ""
        var open = ""
        var approve = ""
        setContent {
            AppTheme {
                title = stringResource(Res.string.plan_approval_title, plan.title)
                open = stringResource(Res.string.plan_read_full)
                approve = stringResource(Res.string.plan_approval_proceed)
                Box(Modifier.width(360.dp)) {
                    PlanApprovalCard(plan, onApprove = { approvals++ }, onOpenInExtension = { opens++ })
                }
            }
        }
        onNodeWithText(title).performClick()
        runOnIdle { assertEquals(1, opens); assertEquals(0, approvals) }
        onNodeWithText(open).performClick()
        runOnIdle { assertEquals(2, opens); assertEquals(0, approvals) }
        onNodeWithText(approve).performClick()
        runOnIdle { assertEquals(2, opens); assertEquals(1, approvals) }
    }

    @Test
    fun nonPendingPlanKeepsItsRealStatusAndHasNoApprovalAction() = runComposeUiTest {
        val status = mutableStateOf("REJECTED")
        var approve = ""
        var rejected = ""
        var approved = ""
        var custom = ""
        setContent {
            AppTheme {
                approve = stringResource(Res.string.plan_approval_proceed)
                rejected = stringResource(Res.string.plan_approval_rejected)
                approved = stringResource(Res.string.plan_approval_approved)
                custom = stringResource(Res.string.plan_approval_status_other, "CUSTOM_STATE")
                PlanApprovalCard(plan.copy(status = status.value), onApprove = {}, onOpenInExtension = {})
            }
        }
        onNodeWithText(rejected).assertIsDisplayed()
        onNodeWithText(approved).assertDoesNotExist()
        onNodeWithText(approve).assertDoesNotExist()
        for (value in listOf("APPROVED", "AUTO_APPROVED", "IN_PROGRESS", "COMPLETED", "VOIDED", "CUSTOM_STATE")) {
            runOnIdle { status.value = value }
            onNodeWithText(approve).assertDoesNotExist()
        }
        onNodeWithText(custom).assertIsDisplayed()
    }

    @Test
    fun errorRecoveryDetailAndDismissAreDistinctAtNarrowWidth() = runComposeUiTest {
        var details = 0
        var recoveries = 0
        var dismissals = 0
        var detail = ""
        var recover = ""
        var dismiss = ""
        setContent {
            AppTheme {
                detail = stringResource(Res.string.errorboard_detail_report)
                recover = stringResource(Res.string.errorboard_continue)
                dismiss = stringResource(Res.string.errorboard_close)
                Box(Modifier.width(280.dp)) {
                    ErrorBoard("The response was interrupted; the original content remains available.",
                        onShowDetail = { details++ }, onDismiss = { dismissals++ },
                        showContinue = true, onContinue = { recoveries++ })
                }
            }
        }
        onNodeWithText(detail).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, details); assertEquals(0, recoveries); assertEquals(0, dismissals) }
        onNodeWithText(recover).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, details); assertEquals(1, recoveries); assertEquals(0, dismissals) }
        onNodeWithContentDescription(dismiss).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, details); assertEquals(1, recoveries); assertEquals(1, dismissals) }
    }

    @Test
    fun mainStatusDoesNotCreateAnIdlePlaceholderAndPreservesRetryDetails() = runComposeUiTest {
        val status = mutableStateOf(TurnStatus.Idle)
        var retryLabel = ""
        setContent {
            AppTheme {
                retryLabel = stringResource(Res.string.status_retrying_progress, "2")
                StatusBar(status.value, statusHint = "2/5|provider rate limit")
            }
        }
        onNodeWithText(retryLabel).assertDoesNotExist()
        runOnIdle { status.value = TurnStatus.Retrying }
        onNodeWithText(retryLabel).assertIsDisplayed()
        onNodeWithText("provider rate limit").assertIsDisplayed()
        for (hiddenStatus in TurnStatus.entries.filter { it != TurnStatus.Retrying }) {
            runOnIdle { status.value = hiddenStatus }
            onNodeWithText(retryLabel).assertDoesNotExist()
            onNodeWithText("provider rate limit").assertDoesNotExist()
        }
    }

    @Test
    fun processDisclosureOffersStandardExpandAndCollapseActions() = runComposeUiTest {
        val expanded = mutableStateOf(false)
        setContent {
            AppTheme {
                ProcessRow(expanded.value, { expanded.value = it }, icon = FeatherIcons.Terminal,
                    label = "Read workspace") { Text("Actual tool input and output") }
            }
        }
        onNodeWithText("Read workspace").performSemanticsAction(SemanticsActions.Expand) { it() }
        onNodeWithText("Actual tool input and output").assertIsDisplayed()
        onNodeWithText("Read workspace").performSemanticsAction(SemanticsActions.Collapse) { it() }
        onNodeWithText("Actual tool input and output").assertDoesNotExist()
    }

    @Test
    fun renderInteractionComponentsInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        setContent {
            AppTheme(mode.value) {
                Row(
                    Modifier.width(960.dp).height(780.dp)
                        .background(LocalMederiColors.current.surfaceWorkspace).padding(24.dp).testTag("interactionSample"),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        QuestionCard(question(multi = true), 0, listOf("First"), {})
                        PlanApprovalCard(plan, onApprove = {}, onOpenInExtension = {})
                        ErrorBoard("The response was interrupted. You can continue from the existing content.", {}, {},
                            showContinue = true, onContinue = {})
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ToolActionGroupRow(
                            isStreaming = false, isRunning = false,
                            group = ToolActionGroup(ToolActionKind.READ, listOf(ToolCallUi("read-fixture", "read_file",
                                ToolCallState.Completed(mapOf("path" to "docs/design/workspace-second-delivery.md"), "Workflow preserved"),
                                target = "docs/design/workspace-second-delivery.md"))),
                        )
                        ProcessRow(true, {}, icon = FeatherIcons.Terminal, label = "Work details") {
                            Text("The main conversation remains available while independent tasks run.",
                                style = WorkspaceStyle.Body, color = LocalMederiColors.current.textSecondary)
                        }
                        StatusBar(TurnStatus.Retrying, statusHint = "2/5|provider rate limit")
                    }
                }
            }
        }
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            onNodeWithText("Which option should we use?").assertIsDisplayed()
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { outputDir ->
                val directory = File(outputDir).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("interactionSample").captureToImage().toAwtImage(), "png",
                    File(directory, "workspace-interactions-${theme.name.lowercase()}.png"))
            }
        }
    }
}
