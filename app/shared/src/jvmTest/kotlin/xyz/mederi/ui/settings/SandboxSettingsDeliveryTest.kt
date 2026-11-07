package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.SandboxStatusInfo
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.UiMessage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SandboxSettingsDeliveryTest {
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(10) }
    private val status = SandboxStatusInfo("fixture", true, "sh", "status fixture")
    private fun state(paths: List<String> = emptyList()) = SandboxSettingsUiState(
        projectId = "project-1", settings = SandboxSettings(extraWritablePaths = paths), status = status)

    @Test
    fun addTrimsRejectsBlankAndDuplicateAndDeleteTargetsExactPath() = runComposeUiTest {
        val paths = mutableStateOf(listOf("/shared", "/shared-more"))
        val writes = mutableListOf<List<String>>()
        var add = ""; var remove = ""; var field = ""
        setContent { AppTheme {
            add = stringResource(Res.string.settings_add)
            remove = stringResource(Res.string.settings_remove)
            field = stringResource(Res.string.settings_sandbox_whitelist_placeholder)
            SandboxSettingsContent(state(paths.value), {}, {}, { writes += it; paths.value = it },
                Modifier.width(460.dp).height(800.dp))
        } }
        onAllNodesWithText(add)[0].performScrollTo().assertIsNotEnabled()
        onNodeWithContentDescription(field).performTextInput("   ")
        onAllNodesWithText(add)[0].assertIsNotEnabled()
        onNodeWithContentDescription(field).performTextReplacement("  /shared  ")
        onAllNodesWithText(add)[0].assertIsNotEnabled()
        onNodeWithContentDescription(field).performTextReplacement("  /new path  ")
        onAllNodesWithText(add)[0].performClick()
        runOnIdle { assertEquals(listOf("/shared", "/shared-more", "/new path"), writes.single()) }
        onAllNodesWithContentDescription(remove)[0].performScrollTo().performClick()
        runOnIdle { assertEquals(listOf("/shared-more", "/new path"), writes.last()) }
    }

    @Test
    fun twoHostModesAreSelectableAndLoadingDoesNotInventASelection() = runComposeUiTest {
        val uiState = mutableStateOf(SandboxSettingsUiState(projectId = "project-1", loading = true))
        var selected: ExecutionPermissionMode? = null
        var full = ""; var approval = ""
        setContent { AppTheme {
            full = stringResource(Res.string.settings_sandbox_full_access)
            approval = stringResource(Res.string.settings_sandbox_needs_approval)
            SandboxSettingsContent(uiState.value, {}, { selected = it }, {}, Modifier.width(460.dp).height(800.dp))
        } }
        onNodeWithText(full).assertDoesNotExist()
        runOnIdle { uiState.value = state() }
        onNodeWithText(approval).assertIsDisplayed()
        onNodeWithText(full).performClick()
        runOnIdle { assertEquals(ExecutionPermissionMode.FULL_ACCESS, selected) }
        runOnIdle { uiState.value = uiState.value.copy(saving = true) }
        onNodeWithText(full).assertIsNotEnabled()
    }

    @Test
    fun refreshAndFailedSaveKeepPathDraftUntilHostConfirmsItsAddition() = runComposeUiTest {
        val uiState = mutableStateOf(state())
        var confirm = false
        var field = ""; var add = ""
        setContent { AppTheme {
            field = stringResource(Res.string.settings_sandbox_whitelist_placeholder)
            add = stringResource(Res.string.settings_add)
            SandboxSettingsContent(uiState.value, {}, {}, { paths ->
                if (confirm) uiState.value = state(paths)
            }, Modifier.width(460.dp).height(800.dp))
        } }
        onNodeWithContentDescription(field).performScrollTo().performTextInput("/draft path")
        runOnIdle { uiState.value = state(listOf("/changed-on-host")) }
        onNodeWithContentDescription(field).assertTextContains("/draft path")
        onAllNodesWithText(add)[0].performScrollTo().performClick()
        runOnIdle { uiState.value = uiState.value.copy(saving = true) }
        onNodeWithContentDescription(field).assertTextContains("/draft path")
        runOnIdle { uiState.value = uiState.value.copy(saving = false,
            error = UiMessage(Res.string.settings_sandbox_save_failed, listOf("save failed"))) }
        onNodeWithContentDescription(field).assertTextContains("/draft path")
        runOnIdle { confirm = true; uiState.value = uiState.value.copy(error = null) }
        onAllNodesWithText(add)[0].performScrollTo().performClick()
        onNodeWithContentDescription(field).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("")))
        onNodeWithText("/draft path").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun longPathsAndLargeFontsKeepPathAndActionsReachable() = runComposeUiTest {
        val path = "/shared/" + "very-long-directory/".repeat(6)
        var removed: List<String>? = null
        var remove = ""; var add = ""
        setContent { AppTheme {
            remove = stringResource(Res.string.settings_remove)
            add = stringResource(Res.string.settings_add)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                SandboxSettingsContent(state(listOf(path)), {}, {}, { removed = it },
                    Modifier.width(280.dp).height(800.dp))
            }
        } }
        onNodeWithText(path).performScrollTo().assertExists()
        onNodeWithContentDescription(remove).performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(emptyList(), removed) }
        onAllNodesWithText(add)[0].performScrollTo().assertIsDisplayed()
    }

}
