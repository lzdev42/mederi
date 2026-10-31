package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RotateCw
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.X
import xyz.mederi.ui.TerminalUiState
import xyz.mederi.ui.TerminalViewModel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.terminalTabTitle
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.ui.components.atoms.WorkspaceIconButton
import xyz.mederi.ui.components.atoms.WorkspacePanelEmptyState
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceNavigationTab
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.term_all_closed
import mederi.app.shared.generated.resources.term_all_closed_hint
import mederi.app.shared.generated.resources.term_click_retry
import mederi.app.shared.generated.resources.term_close_tab
import mederi.app.shared.generated.resources.term_new_tab
import mederi.app.shared.generated.resources.term_reopen
import mederi.app.shared.generated.resources.term_session_ended
import mederi.app.shared.generated.resources.term_starting
import mederi.app.shared.generated.resources.term_tab_ended
import mederi.app.shared.generated.resources.term_unread
import mederi.app.shared.generated.resources.term_unavailable
import mederi.app.shared.generated.resources.term_unavailable_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 终端路由：会话生命周期仍由 TerminalViewModel 管理。
 * 关闭面板不停止 shell；关闭单个 tab 仍转交 VM 的 closeTab，只作用于对应会话。
 */
@Composable
internal fun TerminalPanelContent(
    viewModel: WorkspaceViewModel,
    terminalVm: TerminalViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier,
) {
    if (terminalVm.manager == null) {
        TerminalUnsupportedPlaceholder(colors, modifier)
        return
    }
    val terminalUiState = terminalVm.uiState
    val projects by terminalVm.projects.collectAsState()
    val selectedProjectId by terminalVm.selectedProjectId.collectAsState()

    // 原有 bootstrap / ensureSession 入口与 key 保持不变。
    LaunchedEffect(selectedProjectId, projects) {
        terminalVm.bootstrapIfNeeded(selectedProjectId?.let { id -> projects.find { it.id == id } })
    }
    LaunchedEffect(terminalUiState.activeKey) {
        terminalVm.ensureSession(terminalUiState.activeKey)
    }
    val titles = terminalUiState.tabs.associate { tab ->
        val title = terminalTabTitle(tab.key, terminalVm.sessionTitleOf(tab.key), projects)
        tab.key to stringResource(title.key, *title.args.toTypedArray())
    }
    Column(modifier = modifier.fillMaxSize().background(colors.surfaceWorkspace)) {
        TerminalTabs(
            uiState = terminalUiState,
            titles = titles,
            colors = colors,
            onSelectTab = terminalVm::selectTab,
            onCloseTab = terminalVm::closeTab,
            onNewTab = { terminalVm.addTab() },
        )
        val currentKey = terminalUiState.activeKey
        val currentError = terminalUiState.tabs.firstOrNull { it.key == currentKey }?.error
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                currentKey.isEmpty() -> TerminalCenterHint(
                    title = stringResource(Res.string.term_all_closed),
                    subtitle = stringResource(Res.string.term_all_closed_hint),
                    colors = colors,
                )
                currentError != null -> TerminalErrorView(
                    message = stringResource(currentError.key, *currentError.args.toTypedArray()),
                    colors = colors,
                    onRetry = { terminalVm.retryTab(currentKey) },
                )
                terminalVm.sessionOf(currentKey) != null -> TerminalSessionView(
                    session = terminalVm.sessionOf(currentKey)!!,
                    colors = colors,
                    onRestart = { terminalVm.restartTab(currentKey) },
                )
                else -> TerminalCenterHint(stringResource(Res.string.term_starting), null, colors)
            }
        }
    }
}

/**
 * 纯 tab 视图。选择按钮和关闭按钮是并列点击区域，关闭不会冒泡触发选择。
 * 标题由路由从现有项目/会话数据解析；该组件不访问 VM 或创建会话。
 */
@Composable
internal fun TerminalTabs(
    uiState: TerminalUiState,
    titles: Map<String, String>,
    colors: MederiColors,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeLabel = stringResource(Res.string.term_close_tab)
    val unreadLabel = stringResource(Res.string.term_unread)
    val endedLabel = stringResource(Res.string.term_tab_ended)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
        ) {
            uiState.tabs.forEach { tab ->
                key(tab.key) {
                    val active = tab.key == uiState.activeKey
                    val title = titles[tab.key] ?: tab.key
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!active && tab.unread && !tab.ended) {
                            Box(
                                modifier = Modifier.padding(start = 4.dp, end = 2.dp).size(6.dp)
                                    .clip(CircleShape).background(colors.accentPrimary)
                                    .semantics { contentDescription = unreadLabel },
                            )
                        }
                        WorkspaceNavigationTab(
                            text = title,
                            selected = active,
                            onClick = { onSelectTab(tab.key) },
                            icon = FeatherIcons.Terminal,
                            detail = endedLabel.takeIf { tab.ended },
                        )
                        WorkspaceIconButton(
                            icon = FeatherIcons.X,
                            contentDescription = "$closeLabel: $title",
                            onClick = { onCloseTab(tab.key) },
                            colors = colors,
                        )
                    }
                }
            }
        }
        WorkspaceIconButton(
            icon = FeatherIcons.Plus,
            contentDescription = stringResource(Res.string.term_new_tab),
            onClick = onNewTab,
            colors = colors,
        )
    }
}

@Composable
private fun TerminalSessionView(
    session: xyz.mederi.core.contract.TerminalSession,
    colors: MederiColors,
    onRestart: () -> Unit,
) {
    val isRunning by session.isRunning.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        // 原生终端视图接线与尺寸保持原样，不在表现层重建 session。
        TerminalView(session = session, isDark = colors.isDark, modifier = Modifier.fillMaxSize())
        if (!isRunning) {
            TerminalEndedView(
                colors = colors,
                onRestart = onRestart,
                modifier = Modifier.fillMaxSize()
                    .background(colors.surfaceWorkspace.copy(alpha = 0.88f)),
            )
        }
    }
}

/** 可独立验证的结束提示，重新打开只发出原有 restart 意图。 */
@Composable
internal fun TerminalEndedView(
    colors: MederiColors,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkspacePanelEmptyState(
        icon = FeatherIcons.Terminal,
        title = stringResource(Res.string.term_session_ended),
        modifier = modifier,
        colors = colors,
        action = {
            WorkspaceActionButton(
                text = stringResource(Res.string.term_reopen),
                onClick = onRestart,
                icon = FeatherIcons.RotateCw,
                colors = colors,
            )
        },
    )
}

/** 可独立验证的真实错误消息与重试入口，不以点击整块替代明确动作。 */
@Composable
internal fun TerminalErrorView(
    message: String,
    colors: MederiColors,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkspacePanelEmptyState(
        icon = FeatherIcons.AlertCircle,
        title = message,
        modifier = modifier,
        colors = colors,
        action = {
            WorkspaceActionButton(
                text = stringResource(Res.string.term_click_retry),
                onClick = onRetry,
                icon = FeatherIcons.RotateCw,
                colors = colors,
            )
        },
    )
}

@Composable
internal fun TerminalCenterHint(
    title: String,
    subtitle: String?,
    colors: MederiColors,
    modifier: Modifier = Modifier,
) {
    WorkspacePanelEmptyState(
        icon = FeatherIcons.Terminal,
        title = title,
        hint = subtitle,
        colors = colors,
        modifier = modifier,
    )
}

/** 无能力提示无重试假按钮，不能将遥控端伪装成本地 PTY。 */
@Composable
internal fun TerminalUnsupportedPlaceholder(colors: MederiColors, modifier: Modifier = Modifier) {
    WorkspacePanelEmptyState(
        icon = FeatherIcons.Terminal,
        title = stringResource(Res.string.term_unavailable),
        hint = stringResource(Res.string.term_unavailable_hint),
        colors = colors,
        modifier = modifier.background(colors.surfaceWorkspace),
    )
}
