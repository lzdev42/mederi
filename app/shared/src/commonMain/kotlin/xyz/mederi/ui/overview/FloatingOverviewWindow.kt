package xyz.mederi.ui.overview

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.overview_back_to_chat
import mederi.app.shared.generated.resources.overview_back_to_overview
import mederi.app.shared.generated.resources.overview_hide
import mederi.app.shared.generated.resources.overview_usage_stats_title
import mederi.app.shared.generated.resources.overview_window_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.MederiMinimalIconButton
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.hasRunningSubagents
import xyz.mederi.ui.settings.SessionAgentSettingsDialog

/**
 * 固定右上角的非模态概览窗口；仅显示/隐藏，不再最小化为胶囊。
 * Workspace 持有并持久化共享状态，标题栏开关与窗口关闭按钮读取同一 visible。
 * 子 Agent 行是概览的一行（一体）：隐藏概览即连同子 Agent 行一起隐藏；
 * 隐藏仅停止内容组合（包括后台任务轮询），不清空展开行。
 */
@Composable
fun FloatingOverviewWindow(
    viewModel: WorkspaceViewModel,
    rawVm: RawMessagesViewModel,
    state: OverviewWindowState,
    maxHeight: Dp,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    FollowSubagentActivity(viewModel, state)
    OverviewWindowSurface(
        onClose = { state.visible = false },
        modifier = modifier.padding(top = 48.dp, end = ChatLayout.overviewEndPadding)
            .widthIn(max = (maxWidth - ChatLayout.overviewEndPadding).coerceAtLeast(0.dp))
            .width(ChatLayout.overviewWidth)
            .heightIn(max = (maxHeight - 64.dp).coerceAtLeast(0.dp))
            .zIndex(10f),
    ) {
        OverviewRows(viewModel, rawVm, state)
    }
}

internal enum class CompactOverviewDestination { Overview, UsageStats }

@Composable
internal fun CompactOverviewPage(
    viewModel: WorkspaceViewModel,
    rawVm: RawMessagesViewModel,
    state: OverviewWindowState,
    destination: CompactOverviewDestination,
    onOpenStats: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalMederiColors.current
    FollowSubagentActivity(viewModel, state)
    val showUsage = destination == CompactOverviewDestination.UsageStats
    val backLabel = stringResource(
        if (showUsage) Res.string.overview_back_to_overview else Res.string.overview_back_to_chat,
    )
    val savedPages = rememberSaveableStateHolder()
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(destination) { backFocus.requestFocus() }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(colors.surfaceWorkspace)
            .onPreviewKeyEvent {
                if ((it.key == Key.Escape || it.key == Key.Back) && it.type == KeyEventType.KeyUp) {
                    onBack()
                    true
                } else false
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .padding(horizontal = MederiSpacing.SM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus)) {
                Icon(FeatherIcons.ArrowLeft, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(MederiSpacing.XS))
                Text(backLabel, maxLines = 1)
            }
            Text(
                text = stringResource(
                    if (showUsage) Res.string.overview_usage_stats_title else Res.string.overview_window_title,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = MederiSpacing.XS),
                color = colors.textPrimary,
                style = WorkspaceStyle.Title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = colors.divider)
        savedPages.SaveableStateProvider(destination.name) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(MederiSpacing.SM),
                verticalArrangement = Arrangement.spacedBy(MederiSpacing.Micro),
            ) {
                if (showUsage) CompactUsageStats(viewModel)
                else OverviewRows(viewModel, rawVm, state, onOpenStats)
            }
        }
    }
}

@Composable
private fun FollowSubagentActivity(
    viewModel: WorkspaceViewModel,
    state: OverviewWindowState,
) {
    // 子 Agent 运行开始自动展开，全部结束后仅收起子 Agent 行。
    val conversationId = viewModel.conversationId
    val subagentsRunning = hasRunningSubagents(viewModel.subagents)
    var subagentsWereRunning by remember(conversationId) { mutableStateOf(false) }
    LaunchedEffect(subagentsRunning, conversationId) {
        if (subagentsRunning != subagentsWereRunning) {
            if (subagentsRunning) state.expandedRowIndex = OverviewRowIndex.SUBAGENT
            else if (state.expandedRowIndex == OverviewRowIndex.SUBAGENT) state.expandedRowIndex = null
        }
        subagentsWereRunning = subagentsRunning
    }
}

@Composable
private fun OverviewRows(
    viewModel: WorkspaceViewModel,
    rawVm: RawMessagesViewModel,
    state: OverviewWindowState,
    onOpenStats: (() -> Unit)? = null,
) {
    // 会话子代理设置（含未发送会话的草稿）；入口在子 Agent 行头齿轮
    val conversationId = viewModel.conversationId
    val draftSubagentSettings = viewModel.draftSubagentSettings
    var showSubagentSettings by remember(conversationId, draftSubagentSettings) { mutableStateOf(false) }

    // 行顺序定稿（2026-11）：上下文占用 / 用量 / 子 Agent / 后台任务 / 计划 / 文档 / Todo / MCP / Skill / 原始消息
    OverviewContextRow(
        viewModel = viewModel,
        expanded = state.isRowExpanded(OverviewRowIndex.CONTEXT),
        onToggle = { state.toggleRow(OverviewRowIndex.CONTEXT) },
    )
    OverviewUsageRow(
        viewModel = viewModel,
        expanded = state.isRowExpanded(OverviewRowIndex.USAGE),
        onToggle = { state.toggleRow(OverviewRowIndex.USAGE) },
        onOpenStats = onOpenStats,
    )
    OverviewSubAgentRow(
        viewModel = viewModel,
        expanded = state.isRowExpanded(OverviewRowIndex.SUBAGENT),
        onToggle = { state.toggleRow(OverviewRowIndex.SUBAGENT) },
        onOpenSettings = { showSubagentSettings = true },
    )
    OverviewRunningTaskRow(
        viewModel = viewModel,
        expanded = state.isRowExpanded(OverviewRowIndex.RUNNING_TASK),
        onToggle = { state.toggleRow(OverviewRowIndex.RUNNING_TASK) },
    )
    OverviewPlanRow(
        viewModel = viewModel,
        expanded = state.isRowExpanded(OverviewRowIndex.PLAN),
        onToggle = { state.toggleRow(OverviewRowIndex.PLAN) },
    )
    // 文档（2026-11）：计划以外的会话文档；点击在文档面板（ARTIFACTS，无 dock 按钮）打开阅读。
    OverviewDocumentsRow(
        viewModel = viewModel,
        expanded = state.isRowExpanded(OverviewRowIndex.DOCUMENTS),
        onToggle = { state.toggleRow(OverviewRowIndex.DOCUMENTS) },
    )
    OverviewTodoRow(
        viewModel = viewModel,
        expanded = state.isRowExpanded(OverviewRowIndex.TODO),
        onToggle = { state.toggleRow(OverviewRowIndex.TODO) },
    )
    OverviewMcpRow(
        viewModel = viewModel,
        expanded = state.isRowExpanded(OverviewRowIndex.MCP),
        onToggle = { state.toggleRow(OverviewRowIndex.MCP) },
    )
    OverviewSkillRow(
        viewModel = viewModel,
        expanded = state.isRowExpanded(OverviewRowIndex.SKILL),
        onToggle = { state.toggleRow(OverviewRowIndex.SKILL) },
    )
    OverviewRawMessagesRow(
        viewModel = viewModel,
        rawVm = rawVm,
        expanded = state.isRowExpanded(OverviewRowIndex.RAW_MESSAGES),
        onToggle = { state.toggleRow(OverviewRowIndex.RAW_MESSAGES) },
    )
    if (showSubagentSettings) {
        key(conversationId, draftSubagentSettings) {
            SessionAgentSettingsDialog(conversationId, draftSubagentSettings, onDismiss = { showSubagentSettings = false })
        }
    }
}

/** 概览行的固定顺序索引；[OverviewWindowState.expandedRowIndex] 按这些索引做单展开互斥。 */
internal object OverviewRowIndex {
    const val CONTEXT = 0
    const val USAGE = 1
    const val SUBAGENT = 2
    const val RUNNING_TASK = 3
    const val PLAN = 4
    const val DOCUMENTS = 5
    const val TODO = 6
    const val MCP = 7
    const val SKILL = 8
    const val RAW_MESSAGES = 9
}

private fun OverviewWindowState.isRowExpanded(index: Int) = expandedRowIndex == index

/** 展开一行自动收起其它行；点击已展开的行则折叠（单展开互斥）。 */
private fun OverviewWindowState.toggleRow(index: Int) {
    expandedRowIndex = if (expandedRowIndex == index) null else index
}

/** 概览外壳只消费关闭回调和内容；标题固定，内容区在可用高度内滚动。 */
@Composable
internal fun OverviewWindowSurface(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMederiColors.current
    val shape = RoundedCornerShape(WorkspaceStyle.ObjectRadius)
    Column(
        modifier = modifier.clip(shape).background(colors.surfaceCard)
            .border(1.dp, colors.divider.copy(alpha = 0.55f), shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp)
                .padding(start = MederiSpacing.LG, end = MederiSpacing.XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.overview_window_title),
                color = colors.textSecondary,
                style = WorkspaceStyle.Title,
            )
            Spacer(Modifier.weight(1f))
            MederiMinimalIconButton(
                icon = FeatherIcons.X,
                onClick = onClose,
                contentDescription = stringResource(Res.string.overview_hide),
            )
        }
        Column(
            modifier = Modifier.weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = MederiSpacing.XS, end = MederiSpacing.XS, bottom = MederiSpacing.SM),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.Micro),
            content = content,
        )
    }
}
