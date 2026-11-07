package xyz.mederi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ExecutionPermissionMode
import xyz.mederi.core.contract.models.SandboxSettings
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.appstate.ProjectPermissionState
import xyz.mederi.ui.components.PermissionPlanSelectorContent
import xyz.mederi.ui.components.PermissionPlanSettingsContent
import xyz.mederi.core.contract.models.AgentMode
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.Project
import androidx.compose.ui.graphics.toAwtImage
import java.io.File
import javax.imageio.ImageIO
import xyz.mederi.ui.settings.SandboxSettingsContent
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ProjectPermissionSelectorTest {
    private fun SemanticsNodeInteraction.assertWidthAtMost(max: Dp, density: Float): SemanticsNodeInteraction {
        assertTrue(fetchSemanticsNode().boundsInRoot.width <= max.value * density + 1f)
        return this
    }
    @Test
    fun oneIconOpensIndependentExecutionAndPlanChoicesAtPhoneWidth() = runComposeUiTest {
        val state = ProjectPermissionState(projectId = "a", settings = SandboxSettings())
        val executions = mutableListOf<ExecutionPermissionMode>()
        val plans = mutableListOf<AgentMode>()
        var iconLabel = ""; var full = ""; var scope = ""; var manual = ""; var density = 1f
        setContent { AppTheme {
            density = LocalDensity.current.density
            full = stringResource(Res.string.settings_sandbox_full_access)
            manual = stringResource(Res.string.mode_manual_approve)
            iconLabel = stringResource(Res.string.permission_plan_button_description,
                stringResource(Res.string.settings_sandbox_needs_approval), stringResource(Res.string.mode_auto_approve))
            scope = stringResource(Res.string.execution_permission_project_scope)
            Box(Modifier.width(320.dp)) {
                PermissionPlanSelectorContent(state, AgentMode.AUTONOMOUS, { executions += it }, { plans += it }, {}, {})
            }
        } }
        onNodeWithText(full).assertDoesNotExist()
        onNodeWithText(manual).assertDoesNotExist()
        onNodeWithContentDescription(iconLabel).assertWidthAtMost(48.dp, density).performClick()
        onNodeWithText(scope).assertIsDisplayed().assertWidthAtMost(320.dp, density)
        System.getenv("MEDERI_PERMISSION_PREVIEW_DIR")?.let { location ->
            val directory = File(location).apply { mkdirs() }
            val roots = onAllNodes(isRoot())
            ImageIO.write(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().toAwtImage(), "png", File(directory, "permission-menu-320.png"))
        }
        onNodeWithText(full).performClick()
        runOnIdle { assertEquals(listOf(ExecutionPermissionMode.FULL_ACCESS), executions); assertTrue(plans.isEmpty()) }
        onNodeWithContentDescription(iconLabel).performClick()
        onNodeWithText(manual).performClick()
        runOnIdle { assertEquals(listOf(AgentMode.APPROVAL), plans); assertEquals(1, executions.size) }
    }

    @Test
    fun unknownProjectCannotGrantExecutionAccessButPlanApprovalRemainsIndependent() = runComposeUiTest {
        val state = ProjectPermissionState(projectId = "b")
        var label = ""; var full = ""; var settings = ""; var opens = 0
        setContent { AppTheme {
            label = stringResource(Res.string.permission_plan_button_description,
                stringResource(Res.string.settings_sandbox_unknown), stringResource(Res.string.mode_auto_approve))
            full = stringResource(Res.string.settings_sandbox_full_access)
            settings = stringResource(Res.string.project_settings_title)
            Box(Modifier.width(320.dp)) {
                PermissionPlanSelectorContent(state, AgentMode.AUTONOMOUS,
                    { error("Unloaded project cannot grant access") }, {}, {}, { opens++ })
            }
        } }
        onNodeWithContentDescription(label).performClick()
        onNodeWithText(full).assertDoesNotExist()
        onNodeWithText(settings).performClick()
        runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun phoneSheetDisplaysBothGroupsDirectlyWithoutASecondMenu() = runComposeUiTest {
        var full = ""; var plans = ""; var density = 1f
        setContent { AppTheme {
            full = stringResource(Res.string.settings_sandbox_full_access)
            density = LocalDensity.current.density
            plans = stringResource(Res.string.plan_approval_settings_title)
            Box(Modifier.width(320.dp).testTag("permission-sheet-preview")) {
                PermissionPlanSettingsContent(ProjectPermissionState(projectId = "a", settings = SandboxSettings()),
                    AgentMode.APPROVAL, {}, {}, {}, {})
            }
        } }
        onNodeWithText(full).assertIsDisplayed()
        onNodeWithText(plans).assertIsDisplayed()
        onNodeWithTag("permission-sheet-preview").assertWidthAtMost(320.dp, density)
        System.getenv("MEDERI_PERMISSION_PREVIEW_DIR")?.let { location ->
            val directory = File(location).apply { mkdirs() }
            ImageIO.write(onNodeWithTag("permission-sheet-preview").captureToImage().toAwtImage(), "png", File(directory, "permission-sheet-320.png"))
        }
    }

    @Test
    fun unknownConversationNeverBorrowsAnotherSelectedProjectsPermissions() {
        val other = Project("selected", "Other", "/other", emptyList())
        assertNull(resolvePermissionProjectId("loading-conversation", null, listOf(other), "selected"))
        val unrelated = Conversation("another", "selected", "Other", ConversationStatus.Idle, 0, 0)
        assertNull(resolvePermissionProjectId("loading-conversation", unrelated, listOf(other), "selected"))
        assertEquals("selected", resolvePermissionProjectId(null, null, listOf(other), "selected"))
    }

    @Test
    fun fourRuleListsRemainEditableAtPhoneWidthAndLargeFont() = runComposeUiTest {
        val state = mutableStateOf(ProjectPermissionState(projectId = "a", settings = SandboxSettings()))
        var add = ""; var extra = ""; var deniedPath = ""; var allowed = ""; var deniedCommand = ""
        setContent { AppTheme {
            add = stringResource(Res.string.settings_add)
            extra = stringResource(Res.string.settings_sandbox_whitelist_placeholder)
            deniedPath = stringResource(Res.string.settings_sandbox_denied_paths_placeholder)
            allowed = stringResource(Res.string.settings_sandbox_allowed_commands_placeholder)
            deniedCommand = stringResource(Res.string.settings_sandbox_denied_commands_placeholder)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                SandboxSettingsContent(state.value, {}, {}, { values ->
                    state.value = state.value.copy(settings = state.value.settings!!.copy(extraWritablePaths = values))
                }, Modifier.width(280.dp).height(800.dp), projectName = "Project A",
                    onDeniedPathsChange = { values -> state.value = state.value.copy(settings = state.value.settings!!.copy(deniedPaths = values)) },
                    onAllowedCommandsChange = { values -> state.value = state.value.copy(settings = state.value.settings!!.copy(allowedCommands = values)) },
                    onDeniedCommandsChange = { values -> state.value = state.value.copy(settings = state.value.settings!!.copy(deniedCommands = values)) })
            }
        } }
        val samples = listOf(extra to "/extra", deniedPath to "/private", allowed to "git status", deniedCommand to "git push")
        samples.forEachIndexed { index, (placeholder, value) ->
            onNodeWithContentDescription(placeholder).performScrollTo().performTextInput(value)
            onAllNodesWithText(add)[index].performScrollTo().assertIsDisplayed().performClick()
            onNodeWithText(value).performScrollTo().assertIsDisplayed()
        }
        runOnIdle {
            assertEquals(listOf("/extra"), state.value.settings?.extraWritablePaths)
            assertEquals(listOf("/private"), state.value.settings?.deniedPaths)
            assertEquals(listOf("git status"), state.value.settings?.allowedCommands)
            assertEquals(listOf("git push"), state.value.settings?.deniedCommands)
        }
    }

    @Test
    fun projectSettingsNavigationDoesNotBecomeGlobalSettingsOrChangeProjectIdentity() {
        val route = WorkspaceEffectState()
        route.accept(UiEffect.OpenProjectSettings("a"))
        assertEquals("a", route.projectSettingsProjectId)
        assertFalse(route.settingsVisible)
        route.accept(UiEffect.OpenSettings)
        assertNull(route.projectSettingsProjectId)
        assertTrue(route.settingsVisible)
        route.accept(UiEffect.OpenProjectSettings("b"))
        route.dismissProjectSettings()
        assertNull(route.projectSettingsProjectId)
        assertFalse(route.settingsVisible)
    }
}
