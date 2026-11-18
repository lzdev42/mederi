package xyz.mederi.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.composer_plan_approval_mode
import mederi.app.shared.generated.resources.composer_sandbox_mode
import mederi.app.shared.generated.resources.plan_approval_automatic
import mederi.app.shared.generated.resources.settings_sandbox_needs_approval
import mederi.app.shared.generated.resources.input_expand
import mederi.app.shared.generated.resources.input_collapse
import mederi.app.shared.generated.resources.overview_window_title
import org.jetbrains.compose.resources.getString
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.Workspace
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.composerAutomaticTextHeight
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ComposerHeightTest {
    @Test
    fun automaticCapRespondsToViewportAndFontScale() {
        assertEquals(170.dp, composerAutomaticTextHeight(800.dp, 22.dp))
        assertEquals(140.dp, composerAutomaticTextHeight(400.dp, 22.dp))
        assertEquals(63.dp, composerAutomaticTextHeight(180.dp, 22.dp))
        assertEquals(86.dp, composerAutomaticTextHeight(180.dp, 35.dp))
    }

    @Test
    fun productionComposerGrowsMaximizesAndRetainsDraftThroughResize() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = MockAiCore()
        core.initialize().getOrThrow()
        val project = core.createProject(CreateProjectInput("mederi", "/mock/mederi")).getOrThrow()
        val state = AppState(core, InMemoryPreferencesStore(), scope)
        state.selectProject(project.id)
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val viewportHeight = mutableStateOf(520.dp)
        val viewportWidth = mutableStateOf(720.dp)
        val expandLabel = getString(Res.string.input_expand)
        val collapseLabel = getString(Res.string.input_collapse)
        val sandboxLabel = getString(Res.string.composer_sandbox_mode, getString(Res.string.settings_sandbox_needs_approval))
        val planLabel = getString(Res.string.composer_plan_approval_mode, getString(Res.string.plan_approval_automatic))
        state.selectModel(core.availableModels.value.first())
        lateinit var viewModel: WorkspaceViewModel
        try {
            setContent {
                CompositionLocalProvider(
                    LocalAppState provides state,
                    LocalViewModelStoreOwner provides owner,
                    LocalDensity provides Density(1f),
                ) {
                    AppTheme {
                        viewModel = androidx.lifecycle.viewmodel.compose.viewModel { WorkspaceViewModel(state) }
                        Box(Modifier.size(viewportWidth.value, viewportHeight.value)) {
                            Workspace(
                                viewModel = viewModel,
                                isCompact = viewportWidth.value < 400.dp,
                                projectMenuExpanded = false,
                                onProjectMenuExpandedChange = {},
                            )
                        }
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onNodeWithText("main").isDisplayed() }
            waitUntil(timeoutMillis = 5_000) { onNodeWithText(sandboxLabel).isDisplayed() }
            val branchBounds = onNodeWithText("main").fetchSemanticsNode().boundsInRoot
            val projectBounds = onAllNodesWithText("mederi").fetchSemanticsNodes()
                .map { it.boundsInRoot }
                .minBy { kotlin.math.abs(it.center.y - branchBounds.center.y) }
            assertEquals(projectBounds.center.y, branchBounds.center.y, 1f)
            assertEquals(branchBounds.center.y, onNodeWithText(planLabel).fetchSemanticsNode().boundsInRoot.center.y, 1f)

            val previewDir = System.getenv("MEDERI_COMPOSER_PREVIEW_DIR")?.let { File(it).apply { mkdirs() } }
            previewDir?.let {
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "desktop-empty.png"))
            }
            runOnIdle { viewportWidth.value = 320.dp }
            assertEquals(
                onNodeWithText(sandboxLabel).fetchSemanticsNode().boundsInRoot.center.y,
                onNodeWithText(planLabel).fetchSemanticsNode().boundsInRoot.center.y,
                1f,
            )
            previewDir?.let {
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "mobile-empty.png"))
            }

            val field = onNode(hasSetTextAction())
            val initialHeight = field.fetchSemanticsNode().boundsInRoot.height
            val draft = List(15) { "Line ${it + 1}" }.joinToString("\n")
            field.performTextReplacement(draft)
            val automaticHeight = field.fetchSemanticsNode().boundsInRoot.height
            assertTrue(initialHeight <= 64f)
            assertTrue(automaticHeight > initialHeight)
            assertTrue(automaticHeight <= 170f)

            onNodeWithText(expandLabel).assertIsDisplayed().performClick()
            onNodeWithText(collapseLabel).assertIsDisplayed()
            val maximized = field.fetchSemanticsNode().boundsInRoot
            assertTrue(maximized.height > automaticHeight)

            runOnIdle { viewportHeight.value = 700.dp }
            val resized = field.fetchSemanticsNode().boundsInRoot
            assertEquals(maximized.top, resized.top)
            assertTrue(resized.height > maximized.height)

            onNodeWithText(collapseLabel).performClick()
            onNodeWithText(expandLabel).assertIsDisplayed()
            assertEquals(draft, runOnIdle { viewModel.inputDraft.text })
        } finally {
            runOnIdle { owner.viewModelStore.clear() }
            scope.cancel()
        }
    }

    @Test
    fun firstSnapshotLoadingUsesConversationViewportAndHidesItsControls() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val backend = MockAiCore().apply { initialize().getOrThrow() }
        val firstSnapshot = CompletableDeferred<Unit>()
        val delayedCore = object : AiCore by backend {
            override fun observeConversation(conversationId: String) = flow {
                firstSnapshot.await()
                emitAll(backend.observeConversation(conversationId))
            }
        }
        val state = AppState(delayedCore, InMemoryPreferencesStore(), scope)
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val height = mutableStateOf(420.dp)
        val overviewLabel = getString(Res.string.overview_window_title)
        try {
            setContent {
                CompositionLocalProvider(
                    LocalAppState provides state,
                    LocalViewModelStoreOwner provides owner,
                    LocalDensity provides Density(1f),
                ) {
                    AppTheme(reducedMotion = true) {
                        val viewModel = androidx.lifecycle.viewmodel.compose.viewModel {
                            WorkspaceViewModel(state).apply { attach("conv_102") }
                        }
                        Box(Modifier.size(320.dp, height.value)) {
                            Workspace(
                                viewModel = viewModel,
                                isCompact = true,
                                projectMenuExpanded = false,
                                onProjectMenuExpandedChange = {},
                            )
                        }
                    }
                }
            }

            val progress = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            val initial = progress.fetchSemanticsNode().boundsInRoot
            assertEquals(48f, initial.top)
            assertTrue(initial.height > 300f)
            val composer = onNode(hasSetTextAction(), useUnmergedTree = true)
            composer.assertDoesNotExist()
            onNodeWithText(overviewLabel).assertDoesNotExist()

            runOnIdle { height.value = 620.dp }
            val resized = progress.fetchSemanticsNode().boundsInRoot
            assertEquals(initial.top, resized.top)
            assertEquals(200f, resized.height - initial.height)

            firstSnapshot.complete(Unit)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composer.assertIsDisplayed()
            progress.assertDoesNotExist()
            onNodeWithText(overviewLabel).assertIsDisplayed()
        } finally {
            runOnIdle { owner.viewModelStore.clear() }
            scope.cancel()
        }
    }
}
