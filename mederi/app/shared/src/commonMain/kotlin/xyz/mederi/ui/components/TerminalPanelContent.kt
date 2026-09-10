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
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.X
import xyz.mederi.core.ui.TerminalViewModel
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.core.ui.terminalCwdOf
import xyz.mederi.core.ui.terminalTabTitle
import xyz.mederi.theme.MederiColors

/**
 * 终端面板：多 tab 终端（每 tab 一个独立 shell 会话，数量不设限）。
 * 全部状态机（tab/会话/未读/错误/结束标记）在 [TerminalViewModel]（ViewModelStore 管理，
 * dock 面板关闭重开不丢 tab 结构），本组件只做渲染与事件转发。
 */
@Composable
internal fun TerminalPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    val appState = viewModel.appStateRef
    if (appState.terminalManager == null) {
        TerminalUnsupportedPlaceholder(colors, modifier)
        return
    }

    val terminalVm: TerminalViewModel = viewModel { TerminalViewModel(appState) }
    val projects by appState.projects.collectAsState()
    val selectedProjectId by appState.selectedProjectId.collectAsState()

    // 首次打开面板且零 tab → 自动开一个（点开即是终端）；项目 cwd 优先，无项目 → home
    LaunchedEffect(selectedProjectId, projects) {
        terminalVm.bootstrapIfNeeded(selectedProjectId?.let { id -> projects.find { it.id == id } })
    }

    // 兜底：激活 tab 无会话且无错误（如初始 local tab、重试清空后）→ 拉起
    LaunchedEffect(terminalVm.activeKey) {
        terminalVm.ensureSession(terminalVm.activeKey)
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
            terminalVm.tabKeys.toList().forEach { key ->
                val isActive = key == terminalVm.activeKey
                val isEnded = terminalVm.isEnded(key)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isActive) colors.surfaceCard else colors.surfaceWorkspace)
                        .clickable { terminalVm.selectTab(key) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isActive && terminalVm.isUnread(key) && !isEnded) {
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(colors.accentSecondary)
                        )
                    }
                    Text(
                        text = terminalTabTitle(key, terminalVm.sessionTitleOf(key), projects),
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
                        contentDescription = "关闭终端",
                        tint = colors.textMuted,
                        modifier = Modifier
                            .size(12.dp)
                            .clickable { terminalVm.closeTab(key) }
                    )
                }
            }

            Icon(
                imageVector = FeatherIcons.Plus,
                contentDescription = "新建终端",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { terminalVm.addTab() }
                    .padding(2.dp),
            )
        }

        // ---- 当前 tab 内容 ----
        val currentKey = terminalVm.activeKey
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                currentKey.isEmpty() -> TerminalCenterHint("已关闭全部终端", "点击上方 + 新建终端", colors)

                terminalVm.errorOf(currentKey) != null -> TerminalErrorView(
                    message = terminalVm.errorOf(currentKey)!!,
                    colors = colors,
                    onRetry = { terminalVm.retryTab(currentKey) }
                )

                terminalVm.sessionOf(currentKey) != null -> TerminalSessionView(
                    session = terminalVm.sessionOf(currentKey)!!,
                    colors = colors,
                    onRestart = { terminalVm.restartTab(currentKey) }
                )

                else -> TerminalCenterHint("正在启动终端…", null, colors)
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
                        text = "终端会话已结束",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "重新打开",
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
                text = "点击重试",
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
                contentDescription = "终端",
                tint = colors.textMuted,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "本地终端不可用",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "当前端为遥控端，远程终端将在后续版本接入",
                fontSize = 11.sp,
                color = colors.textMuted
            )
        }
    }
}
