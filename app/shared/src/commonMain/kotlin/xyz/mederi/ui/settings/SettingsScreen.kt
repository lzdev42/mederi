package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.*
import xyz.mederi.ui.components.atoms.WorkspaceIconButton
import xyz.mederi.ui.components.atoms.WorkspaceNavigationTab

internal enum class SettingsTab { PROVIDERS, AGENTS, GENERAL, SANDBOX, BROWSER, REMOTE, SYSTEM }

@Composable
internal fun settingsTabLabel(tab: SettingsTab): String = stringResource(when (tab) {
    SettingsTab.PROVIDERS -> Res.string.settings_tab_providers
    SettingsTab.AGENTS -> Res.string.settings_tab_agents
    SettingsTab.GENERAL -> Res.string.settings_tab_general
    SettingsTab.SANDBOX -> Res.string.settings_tab_sandbox
    SettingsTab.BROWSER -> Res.string.settings_tab_browser
    SettingsTab.REMOTE -> Res.string.settings_tab_remote
    SettingsTab.SYSTEM -> Res.string.settings_tab_system
})

@Composable
private fun settingsTabSubtitle(tab: SettingsTab): String = stringResource(when (tab) {
    SettingsTab.PROVIDERS -> Res.string.settings_subtitle
    SettingsTab.AGENTS -> Res.string.settings_agents_desc
    SettingsTab.GENERAL -> Res.string.settings_appearance_desc
    SettingsTab.SANDBOX -> Res.string.settings_sandbox_desc
    SettingsTab.BROWSER -> Res.string.settings_browser_desc
    SettingsTab.REMOTE -> Res.string.settings_remote_desc
    SettingsTab.SYSTEM -> Res.string.settings_system_title
})

@Composable
fun SettingsDialog(isVisible: Boolean, onClose: () -> Unit) {
    if (!isVisible) return
    var selectedTab by remember { mutableStateOf(SettingsTab.AGENTS) }
    SettingsOverlay(onClose) { panelModifier ->
        SettingsWorkspaceContent(selectedTab, { selectedTab = it }, onClose, panelModifier) { tab, compact ->
            when (tab) {
                SettingsTab.PROVIDERS -> ProviderSettingsPanel()
                SettingsTab.AGENTS -> AgentSettingsPanel(isCompact = compact)
                SettingsTab.GENERAL -> GeneralSettingsPanel(isCompact = compact)
                SettingsTab.SANDBOX -> SandboxSettingsPanel()
                SettingsTab.BROWSER -> BrowserSettingsPanel()
                SettingsTab.REMOTE -> RemoteSettingsPanel(isCompact = compact)
                SettingsTab.SYSTEM -> SystemSettingsPanel(isCompact = compact)
            }
        }
    }
}

/** 设置是工作台内覆盖层；空白内容拦截背景点击，不额外暴露假按钮。 */
@Composable
internal fun SettingsOverlay(onClose: () -> Unit, content: @Composable (Modifier) -> Unit) {
    val colors = LocalMederiColors.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(colors.surfaceOverlay).clickable(onClick = onClose))
        val fullScreen = maxWidth < 760.dp
        val shape = RoundedCornerShape(if (fullScreen) 0.dp else 12.dp)
        content(Modifier.width(if (fullScreen) maxWidth else minOf(1140.dp, maxWidth - 48.dp))
            .height(if (fullScreen) maxHeight else minOf(780.dp, maxHeight - 48.dp))
            .clip(shape).background(colors.surfaceWorkspace)
            .border(if (fullScreen) 0.dp else 1.dp, colors.surfaceCardBorder, shape)
            .pointerInput(Unit) { detectTapGestures {} })
    }
}

/** 同一分类和内容槽驱动宽窄布局，选择仍是View本地状态；业务数据由各Route提供。 */
@Composable
internal fun SettingsWorkspaceContent(
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (SettingsTab, Boolean) -> Unit,
) {
    val colors = LocalMederiColors.current
    val settingsTitle = stringResource(Res.string.settings_title)
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    BoxWithConstraints(modifier.background(colors.surfaceWorkspace)
        .semantics { paneTitle = settingsTitle }
        .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onClose(); true } else false }) {
        val navigationWidth = 184.dp * fontScale.coerceAtMost(1.5f)
        val compact = maxWidth < navigationWidth + 440.dp * fontScale + 48.dp
        if (compact) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(settingsTitle, style = WorkspaceStyle.Title, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    WorkspaceIconButton(FeatherIcons.X, onClose, stringResource(Res.string.close))
                }
                SettingsNavigation(selectedTab, onSelectTab, compact = true)
                SettingsPage(selectedTab, true, {}, showClose = false, modifier = Modifier.weight(1f), content = content)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(navigationWidth).fillMaxHeight().background(colors.surfaceSidebar).padding(12.dp)) {
                    Text(settingsTitle, style = WorkspaceStyle.Title, color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp))
                    SettingsNavigation(selectedTab, onSelectTab, compact = false)
                }
                SettingsPage(selectedTab, false, onClose, modifier = Modifier.weight(1f), content = content)
            }
        }
    }
}

@Composable
private fun SettingsNavigation(selectedTab: SettingsTab, onSelectTab: (SettingsTab) -> Unit, compact: Boolean) {
    val items: @Composable () -> Unit = {
        SettingsTab.entries.forEach { tab ->
            val icon = when (tab) {
                SettingsTab.PROVIDERS -> FeatherIcons.Cpu
                SettingsTab.AGENTS -> FeatherIcons.Users
                SettingsTab.GENERAL -> FeatherIcons.Droplet
                SettingsTab.SANDBOX -> FeatherIcons.Shield
                SettingsTab.BROWSER -> FeatherIcons.Globe
                SettingsTab.REMOTE -> FeatherIcons.Sliders
                SettingsTab.SYSTEM -> FeatherIcons.Activity
            }
            WorkspaceNavigationTab(settingsTabLabel(tab), selectedTab == tab, { onSelectTab(tab) },
                modifier = if (compact) Modifier else Modifier.fillMaxWidth(), icon = icon)
        }
    }
    if (compact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup()
        .padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { items() }
    else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp)) { items() }
}

@Composable
private fun SettingsPage(
    tab: SettingsTab, compact: Boolean, onClose: () -> Unit,
    modifier: Modifier = Modifier, showClose: Boolean = true,
    content: @Composable (SettingsTab, Boolean) -> Unit,
) {
    val colors = LocalMederiColors.current
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 16.dp else 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(settingsTabLabel(tab), style = WorkspaceStyle.PageTitle, color = colors.textPrimary)
                Text(settingsTabSubtitle(tab), style = WorkspaceStyle.Caption, color = colors.textSecondary)
            }
            if (showClose) WorkspaceIconButton(FeatherIcons.X, onClose, stringResource(Res.string.close))
        }
        Box(Modifier.weight(1f).fillMaxWidth()
            .padding(horizontal = if (tab == SettingsTab.PROVIDERS) 0.dp else if (compact) 16.dp else 24.dp,
                vertical = if (tab == SettingsTab.PROVIDERS) 0.dp else 12.dp)) {
            Box(Modifier.widthIn(max = if (tab == SettingsTab.PROVIDERS) 1400.dp else 780.dp).fillMaxSize()) {
                content(tab, compact)
            }
        }
    }
}
