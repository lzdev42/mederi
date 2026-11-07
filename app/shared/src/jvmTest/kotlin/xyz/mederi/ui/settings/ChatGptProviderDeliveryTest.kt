package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ChatGptAuthState
import xyz.mederi.theme.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ChatGptProviderDeliveryTest {
    @Test
    fun authorizationEntryExistsOnlyInsideAddProviderAndRemainsSearchable() = runComposeUiTest {
        val state = mutableStateOf(ProviderSettingsUiState(listOf(ProviderItemUiState("other", "Other provider", false, true)), "other"))
        var add = ""; var name = ""; var search = ""; var action = ""; var starts = 0
        setContent { AppTheme {
            add = stringResource(Res.string.settings_panel_add_provider)
            name = stringResource(Res.string.chatgpt_provider_name)
            search = stringResource(Res.string.provider_catalog_search)
            action = stringResource(Res.string.chatgpt_catalog_action)
            Box(Modifier.width(900.dp).height(650.dp).background(LocalMederiColors.current.surfaceWorkspace).testTag("preview")) {
                ProviderSettingsContent(state.value, listOf("OpenAI", "Other API"), {},
                    { state.value = state.value.copy(presetQuery = it) },
                    { state.value = state.value.copy(page = ProviderPage.Catalog) }, {}, {}, {}, {}, {},
                    detail = { _, _ -> }, custom = {}, preset = {}, onChatGpt = { starts++ })
            }
        } }
        onNodeWithText(name).assertDoesNotExist()
        onNodeWithText(add).performClick()
        onNodeWithText(name).assertIsDisplayed()
        onNodeWithText(search).performTextInput("ChatGPT")
        onNodeWithText(action).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, starts) }
        onNodeWithText("Other API").assertDoesNotExist()
        System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { dir ->
            val directory = File(dir).apply { mkdirs() }
            ImageIO.write(onNodeWithTag("preview").captureToImage().toAwtImage(), "png", File(directory, "chatgpt-provider-catalog.png"))
        }
    }

    @Test
    fun pendingConnectionKeepsRetryCopyCancelAndFailureWithinDetailWidth() = runComposeUiTest {
        var open = ""; var copy = ""; var cancel = ""; var failure = ""
        val actions = mutableListOf<String>()
        setContent { AppTheme {
            open = stringResource(Res.string.chatgpt_open_browser)
            copy = stringResource(Res.string.chatgpt_copy_link)
            cancel = stringResource(Res.string.chatgpt_cancel)
            failure = stringResource(Res.string.chatgpt_browser_open_failed)
            Box(Modifier.width(420.dp).background(LocalMederiColors.current.surfaceWorkspace).padding(12.dp).testTag("preview")) {
                ChatGptAuthorizationContent(ChatGptAuthState("oauth", "PENDING", "https://auth.openai.com/authorize"),
                    null, false, true, {}, { actions += "open" }, { actions += "copy" }, { actions += "cancel" }, {}, {}, {})
            }
        } }
        onNodeWithText(failure).assertIsDisplayed()
        onNodeWithText(open).assertIsDisplayed().performClick()
        onNodeWithText(copy).assertIsDisplayed().performClick()
        onNodeWithText(cancel).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(listOf("open", "copy", "cancel"), actions) }
        val parent = onNodeWithTag("preview").fetchSemanticsNode().boundsInRoot
        listOf(open, copy, cancel, failure).forEach {
            val bounds = onNodeWithText(it).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= parent.left && bounds.right <= parent.right)
        }
        System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { dir ->
            val directory = File(dir).apply { mkdirs() }
            ImageIO.write(onNodeWithTag("preview").captureToImage().toAwtImage(), "png", File(directory, "chatgpt-provider-connection.png"))
        }
    }

    @Test
    fun catalogHidesChatGptEntryWhenLoginUnsupported() = runComposeUiTest {
        val state = mutableStateOf(ProviderSettingsUiState(page = ProviderPage.Catalog))
        var name = ""; var search = ""; var action = ""
        setContent { AppTheme {
            name = stringResource(Res.string.chatgpt_provider_name)
            search = stringResource(Res.string.provider_catalog_search)
            action = stringResource(Res.string.chatgpt_catalog_action)
            Box(Modifier.width(900.dp).height(650.dp).background(LocalMederiColors.current.surfaceWorkspace)) {
                ProviderSettingsContent(state.value, listOf("OpenAI", "Other API"), {},
                    { state.value = state.value.copy(presetQuery = it) },
                    { state.value = state.value.copy(page = ProviderPage.Catalog) }, {}, {}, {}, {}, {},
                    detail = { _, _ -> }, custom = {}, preset = {}, onChatGpt = null)
            }
        } }
        // 遥控端（supportsChatGptLogin=false → onChatGpt=null）目录不渲染 ChatGPT 授权行。
        onNodeWithText(name).assertDoesNotExist()
        onNodeWithText(search).performTextInput("ChatGPT")
        // 搜索后输入框自身文本即 "ChatGPT"，故用行操作文案断言授权行仍不渲染。
        onNodeWithText(action).assertDoesNotExist()
    }
}
