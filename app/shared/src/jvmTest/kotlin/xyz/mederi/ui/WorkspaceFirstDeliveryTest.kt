package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Users
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.input_send
import mederi.app.shared.generated.resources.input_stop
import mederi.app.shared.generated.resources.message_details
import mederi.app.shared.generated.resources.overview_hide
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.AppTheme
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.AssistantMessageFooter
import xyz.mederi.ui.components.ChipSelectorPill
import xyz.mederi.ui.components.DocumentArtifactCard
import xyz.mederi.ui.components.SendButton
import xyz.mederi.ui.overview.OverviewAccordionRow
import xyz.mederi.ui.overview.OverviewWindowSurface
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 真实Compose组件行为回归；样例内容只用于测试，不进入生产任务数据。 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceFirstDeliveryTest {
    @Test
    fun overviewCanScrollWithoutLosingCloseAction() = runComposeUiTest {
        var closeLabel = ""
        var closed = false
        setContent {
            AppTheme {
                closeLabel = stringResource(Res.string.overview_hide)
                OverviewWindowSurface(
                    onClose = { closed = true },
                    modifier = Modifier.width(292.dp).heightIn(max = 240.dp),
                ) {
                    repeat(20) { index -> Text("Task $index", modifier = Modifier.height(40.dp)) }
                }
            }
        }
        val closeTop = onNodeWithContentDescription(closeLabel).fetchSemanticsNode().boundsInRoot.top
        onNodeWithText("Task 19").performScrollTo().assertIsDisplayed()
        onNodeWithContentDescription(closeLabel).assertIsDisplayed()
        assertEquals(closeTop, onNodeWithContentDescription(closeLabel).fetchSemanticsNode().boundsInRoot.top)
        onNodeWithContentDescription(closeLabel).performClick()
        runOnIdle { assertTrue(closed) }
    }

    @Test
    fun overviewDisclosureKeepsSummaryVisibleAndDetailsUserControlled() = runComposeUiTest {
        val expanded = mutableStateOf(false)
        setContent {
            AppTheme {
                Box(Modifier.width(292.dp)) {
                    OverviewAccordionRow(
                        icon = FeatherIcons.Users,
                        title = "Agents",
                        summary = { Text("1 running", color = LocalMederiColors.current.accentText, style = WorkspaceStyle.Caption) },
                        expanded = expanded.value,
                        onToggle = { expanded.value = !expanded.value },
                    ) { Text("Independent background task") }
                }
            }
        }
        onNodeWithText("1 running").assertIsDisplayed()
        onNodeWithText("Independent background task").assertDoesNotExist()
        onNodeWithText("Agents").performClick()
        onNodeWithText("Independent background task").assertIsDisplayed()
        onNodeWithText("Agents").performClick()
        onNodeWithText("Independent background task").assertDoesNotExist()
        onNodeWithText("1 running").assertIsDisplayed()
    }

    @Test
    fun inputWithContentStillSendsDuringMainActivityAndEmptyInputCanStop() = runComposeUiTest {
        val hasContent = mutableStateOf(true)
        val isActive = mutableStateOf(true)
        var sendLabel = ""
        var stopLabel = ""
        var submits = 0
        setContent {
            AppTheme {
                sendLabel = stringResource(Res.string.input_send)
                stopLabel = stringResource(Res.string.input_stop)
                SendButton(
                    isStreaming = isActive.value,
                    hasContent = hasContent.value,
                    canSend = hasContent.value,
                    onSubmit = { submits++ },
                )
            }
        }
        onNodeWithContentDescription(sendLabel).assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, submits); hasContent.value = false }
        onNodeWithContentDescription(stopLabel).assertIsEnabled().performClick()
        runOnIdle { assertEquals(2, submits); isActive.value = false }
        onNodeWithContentDescription(sendLabel).assertIsNotEnabled()
    }

    @Test
    fun responseDiagnosticsAreAvailableWithoutAlwaysOccupyingMessageSpace() = runComposeUiTest {
        var detailsLabel = ""
        setContent {
            AppTheme {
                detailsLabel = stringResource(Res.string.message_details)
                Box(Modifier.width(480.dp)) {
                    AssistantMessageFooter(footer = AssistantFooterInfo(
                        modelName = "Model-FIXTURE", agentMode = "AUTONOMOUS",
                        thinkingLevel = "HIGH", durationMs = 1200,
                    ))
                }
            }
        }
        onNodeWithText("Model-FIXTURE", substring = true).assertDoesNotExist()
        onNodeWithContentDescription(detailsLabel).performClick()
        onNodeWithText("Model-FIXTURE", substring = true).assertIsDisplayed()
        onNodeWithContentDescription(detailsLabel).performClick()
        onNodeWithText("Model-FIXTURE", substring = true).assertDoesNotExist()
    }

    @Test
    fun scaledTextCanIncreaseControlHeightInsteadOfBeingClipped() = runComposeUiTest {
        val fontScale = mutableStateOf(1f)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale.value)) {
                    Box(Modifier.testTag("scalingControl")) {
                        ChipSelectorPill(label = "Choose model")
                    }
                }
            }
        }
        val normalHeight = onNodeWithTag("scalingControl").fetchSemanticsNode().boundsInRoot.height
        runOnIdle { fontScale.value = 2f }
        val scaledHeight = onNodeWithTag("scalingControl").fetchSemanticsNode().boundsInRoot.height
        assertTrue(scaledHeight > normalHeight, "control should grow from $normalHeight to fit scaled text, actual $scaledHeight")
    }

    @Test
    fun themedComponentsKeepDocumentAndSelectionActionsReachable() = runComposeUiTest {
        val theme = mutableStateOf(AppThemeMode.DARK)
        var opens = 0
        var selections = 0
        setContent {
            AppTheme(theme.value) {
                Row(
                    Modifier.width(800.dp).height(400.dp)
                        .background(LocalMederiColors.current.surfaceWorkspace)
                        .padding(24.dp).testTag("sample"),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Independent tasks, uninterrupted conversation", style = WorkspaceStyle.MessageBody,
                            color = LocalMederiColors.current.textPrimary)
                        DocumentArtifactCard(
                            title = "Research report", lineCount = 42, charCount = 1800,
                            createdAt = 0, isStreaming = false, isCompleted = true,
                            onOpenInExtension = { opens++ },
                        )
                        ChipSelectorPill(label = "Choose model", onClick = { selections++ })
                        AssistantMessageFooter(footer = AssistantFooterInfo(modelName = "Model-FIXTURE"))
                    }
                    OverviewWindowSurface(onClose = {}, modifier = Modifier.width(260.dp).heightIn(max = 320.dp)) {
                        OverviewAccordionRow(
                            icon = FeatherIcons.Users, title = "Agents", summary = { Text("1 running", color = LocalMederiColors.current.accentText, style = WorkspaceStyle.Caption) },
                            expanded = true, onToggle = {},
                        ) { Text("Research continues in the background", style = WorkspaceStyle.Caption,
                            modifier = Modifier.padding(8.dp), color = LocalMederiColors.current.textSecondary) }
                    }
                }
            }
        }
        for (mode in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { theme.value = mode }
            onNodeWithText("Research report").performClick()
            onNodeWithText("Choose model").performClick()
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { outputDir ->
                val directory = File(outputDir).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("sample").captureToImage().toAwtImage(), "png",
                    File(directory, "workspace-components-${mode.name.lowercase()}.png"))
            }
        }
        runOnIdle { assertEquals(2, opens); assertEquals(2, selections) }
    }
}
