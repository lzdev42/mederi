package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.slash_group_commands
import mederi.app.shared.generated.resources.slash_group_plugins
import mederi.app.shared.generated.resources.slash_hint_cancel
import mederi.app.shared.generated.resources.slash_hint_cancel_keys
import mederi.app.shared.generated.resources.slash_hint_confirm
import mederi.app.shared.generated.resources.slash_hint_confirm_keys
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.theme.AppTheme
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.command.CommandGroup
import xyz.mederi.ui.components.command.SlashCommandItem
import xyz.mederi.ui.components.command.SlashCommandMenu
import xyz.mederi.ui.components.command.SlashCommandRegistry

@OptIn(ExperimentalTestApi::class)
class SlashCommandMenuTest {

    @Test
    fun commandGroupAndFooterRemainSeparatedWithoutOverlap() = runComposeUiTest {
        val items = SlashCommandRegistry.filter(
            SlashCommandRegistry.BUILTIN_COMMANDS,
            query = "j",
            prefixOnly = true,
        )
        var groupCommands = ""
        var confirmKeys = ""
        var confirmAction = ""
        var cancelKeys = ""
        var cancelAction = ""
        val selectedIds = mutableListOf<String>()

        setContent {
            AppTheme {
                groupCommands = stringResource(Res.string.slash_group_commands)
                confirmKeys = stringResource(Res.string.slash_hint_confirm_keys)
                confirmAction = stringResource(Res.string.slash_hint_confirm)
                cancelKeys = stringResource(Res.string.slash_hint_cancel_keys)
                cancelAction = stringResource(Res.string.slash_hint_cancel)
                Box(Modifier.width(720.dp)) {
                    SlashCommandMenu(
                        items = items,
                        selectedIndex = 0,
                        onSelect = { selectedIds += it.id },
                        modifier = Modifier.testTag("slashMenu"),
                    )
                }
            }
        }

        val menuBounds = onNodeWithTag("slashMenu").fetchSemanticsNode().boundsInRoot
        val groupBounds = onNodeWithText(groupCommands, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val planBounds = onNodeWithText("plan", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val skillBounds = onNodeWithText("skill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val confirmKeyBounds = onNodeWithText(confirmKeys, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val confirmTextBounds = onNodeWithText(confirmAction, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cancelKeyBounds = onNodeWithText(cancelKeys, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cancelTextBounds = onNodeWithText(cancelAction, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertTrue(groupBounds.bottom <= planBounds.top, "Group header must sit above first item: $groupBounds vs $planBounds")
        assertTrue(planBounds.bottom <= skillBounds.top, "First item must sit above second item: $planBounds vs $skillBounds")
        assertTrue(skillBounds.bottom < confirmKeyBounds.top, "Command list must sit strictly above confirm key hint: $skillBounds vs $confirmKeyBounds")
        assertTrue(skillBounds.bottom < cancelKeyBounds.top, "Command list must sit strictly above cancel key hint: $skillBounds vs $cancelKeyBounds")
        assertTrue(confirmKeyBounds.bottom <= menuBounds.bottom && confirmTextBounds.bottom <= menuBounds.bottom)
        assertTrue(cancelKeyBounds.bottom <= menuBounds.bottom && cancelTextBounds.bottom <= menuBounds.bottom)
        assertTrue(confirmTextBounds.right < cancelKeyBounds.left, "Left and right footer hints must stay cleanly separated")

        onNodeWithText("plan").assertIsSelected()
        onNodeWithText("skill").assertIsNotSelected().performClick()
        runOnIdle { assertEquals(listOf("skill"), selectedIds) }
    }

    @Test
    fun longListPinsFooterAndAutoScrollsSelectedItemIntoView() = runComposeUiTest {
        val skills = (1..12).map { index ->
            SkillItem(
                name = "custom-skill-$index",
                description = "Run automated workflow #$index with extended details for ellipsis testing",
            )
        }
        val items = SlashCommandRegistry.getAllCommands(skills)
        val selectedIndex = mutableStateOf(0)
        var confirmKeys = ""
        var cancelKeys = ""

        setContent {
            AppTheme {
                confirmKeys = stringResource(Res.string.slash_hint_confirm_keys)
                cancelKeys = stringResource(Res.string.slash_hint_cancel_keys)
                Box(Modifier.width(520.dp)) {
                    SlashCommandMenu(
                        items = items,
                        selectedIndex = selectedIndex.value,
                        onSelect = {},
                        modifier = Modifier.testTag("longSlashMenu"),
                    )
                }
            }
        }

        val menuBounds = onNodeWithTag("longSlashMenu").fetchSemanticsNode().boundsInRoot
        val confirmKeyBounds = onNodeWithText(confirmKeys, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cancelKeyBounds = onNodeWithText(cancelKeys, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(confirmKeyBounds.bottom <= menuBounds.bottom)
        assertTrue(cancelKeyBounds.bottom <= menuBounds.bottom)

        val targetIdx = items.lastIndex - 1
        val targetLabel = items[targetIdx].label
        runOnIdle { selectedIndex.value = targetIdx }
        waitForIdle()

        onNodeWithText(targetLabel).assertIsSelected().assertIsDisplayed()
        val targetBounds = onNodeWithText(targetLabel, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            targetBounds.top >= menuBounds.top && targetBounds.bottom <= confirmKeyBounds.top,
            "Selected item scrolled to '$targetLabel' must lie visibly above footer: target=$targetBounds, confirm=$confirmKeyBounds",
        )
    }

    @Test
    fun narrowWidthMenuFitsWithoutHorizontalOverflow() = runComposeUiTest {
        val items = SlashCommandRegistry.BUILTIN_COMMANDS
        var confirmAction = ""
        var cancelAction = ""

        setContent {
            AppTheme {
                confirmAction = stringResource(Res.string.slash_hint_confirm)
                cancelAction = stringResource(Res.string.slash_hint_cancel)
                Box(Modifier.width(300.dp)) {
                    SlashCommandMenu(
                        items = items,
                        selectedIndex = 0,
                        onSelect = {},
                        modifier = Modifier.testTag("narrowMenu"),
                    )
                }
            }
        }

        val menuBounds = onNodeWithTag("narrowMenu").fetchSemanticsNode().boundsInRoot
        val confirmTextBounds = onNodeWithText(confirmAction, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cancelTextBounds = onNodeWithText(cancelAction, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertTrue(confirmTextBounds.left >= menuBounds.left && cancelTextBounds.right <= menuBounds.right)
        assertTrue(confirmTextBounds.right < cancelTextBounds.left)
    }

    @Test
    fun renderSlashCommandMenuPreviewsInBothThemes() = runComposeUiTest {
        val shortItems = SlashCommandRegistry.filter(
            SlashCommandRegistry.BUILTIN_COMMANDS,
            query = "j",
            prefixOnly = true,
        )
        val multiGroupItems = SlashCommandRegistry.getAllCommands(
            listOf(
                SkillItem("compose-skill", "Jetpack Compose architecture & UI multiplatform conventions"),
                SkillItem("android-cli", "Create, run, and inspect Android projects via android CLI"),
            )
        ) + SlashCommandItem(
            id = "mcp-custom",
            label = "figma",
            group = CommandGroup.PLUGIN,
            description = "Inspect components & tokens from connected Figma workspace",
            iconBadge = "AI",
            insertText = "/figma ",
        )

        val mode = mutableStateOf(AppThemeMode.DARK)
        var pluginGroup = ""

        setContent {
            AppTheme(mode.value) {
                pluginGroup = stringResource(Res.string.slash_group_plugins)
                Column(
                    modifier = Modifier
                        .width(760.dp)
                        .background(LocalMederiColors.current.surfaceWorkspace)
                        .padding(20.dp)
                        .testTag("slashMenuPreview"),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(
                        text = "Query: j (short list)",
                        style = WorkspaceStyle.Caption,
                        color = LocalMederiColors.current.textSecondary,
                    )
                    SlashCommandMenu(
                        items = shortItems,
                        selectedIndex = 0,
                        onSelect = {},
                    )
                    Text(
                        text = "Query: / (grouped commands + skills)",
                        style = WorkspaceStyle.Caption,
                        color = LocalMederiColors.current.textSecondary,
                    )
                    SlashCommandMenu(
                        items = multiGroupItems,
                        selectedIndex = 4,
                        onSelect = {},
                    )
                }
            }
        }

        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            onNodeWithText(pluginGroup, useUnmergedTree = true).assertIsDisplayed()
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                ImageIO.write(
                    onNodeWithTag("slashMenuPreview").captureToImage().toAwtImage(),
                    "png",
                    File(directory, "slash-command-menu-${theme.name.lowercase()}.png"),
                )
            }
        }
    }
}
