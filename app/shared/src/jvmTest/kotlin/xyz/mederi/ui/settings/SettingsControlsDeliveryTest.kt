package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.settings_stepper_decrease
import mederi.app.shared.generated.resources.settings_stepper_increase
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsControlsDeliveryTest {
    @Test
    fun stepperDisablesEachBoundaryAndEmitsOnlyTheNextValue() = runComposeUiTest {
        val value = mutableStateOf(1)
        val updates = mutableListOf<Int>()
        var decrease = ""
        var increase = ""
        setContent { AppTheme {
            decrease = stringResource(Res.string.settings_stepper_decrease)
            increase = stringResource(Res.string.settings_stepper_increase)
            NumberStepper(value.value, { updates += it; value.value = it }, minValue = 1, maxValue = 2)
        } }
        onNodeWithContentDescription(decrease).assertIsNotEnabled()
        onNodeWithContentDescription(increase).assertIsEnabled().performClick()
        onNodeWithText("2").assertIsDisplayed()
        onNodeWithContentDescription(increase).assertIsNotEnabled().performTouchInput { click() }
        onNodeWithContentDescription(decrease).assertIsEnabled().performClick()
        onNodeWithContentDescription(decrease).assertIsNotEnabled().performTouchInput { click() }
        runOnIdle { assertEquals(listOf(2, 1), updates) }
    }

    @Test
    fun disabledStepperKeepsItsValueAndDoesNotEmitChanges() = runComposeUiTest {
        val enabled = mutableStateOf(false)
        val updates = mutableListOf<Int>()
        var decrease = ""
        var increase = ""
        setContent { AppTheme {
            decrease = stringResource(Res.string.settings_stepper_decrease)
            increase = stringResource(Res.string.settings_stepper_increase)
            NumberStepper(3, { updates += it }, minValue = 1, maxValue = 5, enabled = enabled.value)
        } }
        onNodeWithContentDescription(decrease).assertIsNotEnabled().performTouchInput { click() }
        onNodeWithContentDescription(increase).assertIsNotEnabled().performTouchInput { click() }
        onNodeWithText("3").assertIsDisplayed()
        runOnIdle { assertTrue(updates.isEmpty()); enabled.value = true }
        onNodeWithContentDescription(decrease).assertIsEnabled().performClick()
        runOnIdle { assertEquals(listOf(2), updates) }
    }

    @Test
    fun toggleExposesRadioSelectionAndWaitsForItsExternalState() = runComposeUiTest {
        val selected = mutableStateOf(0)
        val choices = mutableListOf<Int>()
        setContent { AppTheme {
            SegmentedPillToggle(selected.value, listOf("First fixture", "Second fixture"), { choices += it })
        } }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        onAllNodes(radio).assertCountEquals(2)
        onNodeWithText("First fixture").assertIsSelected()
        onNodeWithText("Second fixture").assertIsNotSelected().performClick()
        onNodeWithText("First fixture").assertIsSelected()
        runOnIdle { assertEquals(listOf(1), choices); selected.value = 1 }
        onNodeWithText("Second fixture").assertIsSelected()
        onNodeWithText("First fixture").assertIsNotSelected()
    }

    @Test
    fun narrowToggleWrapsOptionsAndKeepsEveryChoiceReachable() = runComposeUiTest {
        val selected = mutableStateOf(0)
        setContent { AppTheme {
            Box(Modifier.width(150.dp)) {
                SegmentedPillToggle(selected.value, listOf("Primary fixture", "Alternate fixture"), { selected.value = it })
            }
        } }
        val first = onNodeWithText("Primary fixture").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText("Alternate fixture").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(second.top >= first.bottom, "Narrow options should use separate rows")
        onNodeWithText("Alternate fixture").performClick().assertIsSelected()
    }
}
