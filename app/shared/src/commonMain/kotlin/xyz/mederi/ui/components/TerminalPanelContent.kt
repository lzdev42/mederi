package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.X
import xyz.mederi.ui.TerminalViewModel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.terminalCwdOf
import xyz.mederi.ui.terminalTabTitle
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.MederiPanelHeaderIconButton
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.term_all_closed
import mederi.app.shared.generated.resources.term_all_closed_hint
import mederi.app.shared.generated.resources.term_click_retry
import mederi.app.shared.generated.resources.term_close_tab
import mederi.app.shared.generated.resources.term_new_tab
import mederi.app.shared.generated.resources.term_reopen
import mederi.app.shared.generated.resources.term_session_ended
import mederi.app.shared.generated.resources.term_starting
import mederi.app.shared.generated.resources.term_title
import mederi.app.shared.generated.resources.term_unavailable
import mederi.app.shared.generated.resources.term_unavailable_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 终端面板：多 tab 终端（每 tab 一个独立 shell 会话，数量不设限）。
 * 全部状态机（tab/会话/未读/错误/结束标记）在 [TerminalViewModel]（ViewModelStore 管理，
 * dock 面板关闭重开不丢 tab 结构），本组件只做渲染与事件转发。
 */
@Composable
internal fun TerminalPanelContent(
    viewModel: WorkspaceViewModel,
    terminalVm: TerminalViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    // 状态机在 TerminalViewModel（Route 层创建，ViewModelStore 管理生命周期），本组件只渲染与事件转发
    if (terminalVm.manager == null) {
        TerminalUnsupportedPlaceholder(colors, modifier)
        return
    }

    val terminalUiState = terminalVm.uiState
    val projects by terminalVm.projects.collectAsState()
    val selectedProjectId by terminalVm.selectedProjectId.collectAsState()

    // 首次打开面板且零 tab → 自动开一个（点开即是终端）；项目 cwd 优先，无项目 → home
    LaunchedEffect(selectedProjectId, projects) {
        terminalVm.bootstrapIfNeeded(selectedProjectId?.let { id -> projects.find { it.id == id } })
    }

    // 兜底：激活 tab 无会话且无错误（如初始 local tab、重试清空后）→ 拉起
    LaunchedEffect(terminalUiState.activeKey) {
        terminalVm.ensureSession(terminalUiState.activeKey)
    }

    Column(modifier = modifier.fillMaxSize().background(colors.surfaceWorkspace)) {
        // ---- Tab 栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            terminalUiState.tabs.forEach { tab ->
                val key = tab.key
                val isActive = key == terminalUiState.activeKey
                val isEnded = tab.ended
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isActive) colors.surfaceCard else colors.surfaceWorkspace)
                        .clickable { terminalVm.selectTab(key) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isActive && tab.unread && !isEnded) {
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(colors.accentSecondary)
                        )
                    }
                    val tabTitle = terminalTabTitle(key, terminalVm.sessionTitleOf(key), projects)
                    Text(
                        text = stringResource(tabTitle.key, *tabTitle.args.toTypedArray()),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                        color = when {
                            isEnded -> colors.textMuted
                            isActive -> colors.textPrimary
                            else -> colors.textSecondary
                        },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(Res.string.term_close_tab),
                        tint = colors.textMuted,
                        modifier = Modifier
                            .size(12.dp)
                            .clickable { terminalVm.closeTab(key) }
                    )
                }
            }

            // 新建 tab 按钮（收敛为 MederiPanelHeaderIconButton，获得 hover 反馈）
            MederiPanelHeaderIconButton(
                icon = FeatherIcons.Plus,
                onClick = { terminalVm.addTab() },
                contentDescription = stringResource(Res.string.term_new_tab),
            )
        }

        // ---- 当前 tab 内容 ----
        val currentKey = terminalUiState.activeKey
        val currentError = terminalUiState.tabs.firstOrNull { it.key == currentKey }?.error
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                currentKey.isEmpty() -> TerminalCenterHint(stringResource(Res.string.term_all_closed), stringResource(Res.string.term_all_closed_hint), colors)

                currentError != null -> TerminalErrorView(
                    message = stringResource(currentError.key, *currentError.args.toTypedArray()),
                    colors = colors,
                    onRetry = { terminalVm.retryTab(currentKey) }
                )

                terminalVm.sessionOf(currentKey) != null -> TerminalSessionView(
                    session = terminalVm.sessionOf(currentKey)!!,
                    colors = colors,
                    onRestart = { terminalVm.restartTab(currentKey) }
                )

                else -> TerminalCenterHint(stringResource(Res.string.term_starting), null, colors)
            }
        }
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
        TerminalView(
            session = session,
            isDark = colors.isDark,
            modifier = Modifier.fillMaxSize(),
        )
        if (!isRunning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surfaceWorkspace.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(Res.string.term_session_ended),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.term_reopen),
                        fontSize = 12.sp,
                        color = colors.accentSecondary,
                        modifier = Modifier.clickable { onRestart() }
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalErrorView(message: String, colors: MederiColors, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(text = message, fontSize = 12.sp, color = colors.textSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.term_click_retry),
                fontSize = 12.sp,
                color = colors.accentSecondary,
                modifier = Modifier.clickable { onRetry() }
            )
        }
    }
}

@Composable
private fun TerminalCenterHint(title: String, subtitle: String?, colors: MederiColors) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, fontSize = 12.sp, color = colors.textSecondary)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, fontSize = 11.sp, color = colors.textMuted)
            }
        }
    }
}

/** 无终端能力端（wasm/移动遥控端）占位。 */
@Composable
private fun TerminalUnsupportedPlaceholder(colors: MederiColors, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(colors.surfaceWorkspace),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = FeatherIcons.Terminal,
                contentDescription = stringResource(Res.string.term_title),
                tint = colors.textMuted,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.term_unavailable),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.term_unavailable_hint),
                fontSize = 11.sp,
                color = colors.textMuted
            )
        }
    }
}
