package xyz.mederi.ui.overview

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import compose.icons.FeatherIcons
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.overview_hide
import mederi.app.shared.generated.resources.overview_window_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.MederiMinimalIconButton
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.WorkspaceViewModel

/**
 * 固定右上角的非模态概览窗口；仅显示/隐藏，不再最小化为胶囊。
 * Workspace 持有并持久化共享状态，标题栏开关与窗口关闭按钮读取同一 visible。
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
    OverviewWindowSurface(
        onClose = { state.visible = false },
        modifier = modifier.padding(top = 48.dp, end = 16.dp)
            .widthIn(max = (maxWidth - 16.dp).coerceAtLeast(0.dp))
            .width(292.dp)
            .heightIn(max = (maxHeight - 64.dp).coerceAtLeast(0.dp))
            .zIndex(10f),
    ) {
        OverviewRunningTaskRow(
            viewModel = viewModel,
            expanded = state.expandedRowIndex == 0,
            onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 0) null else 0 },
        )
        OverviewContextRow(
            viewModel = viewModel,
            expanded = state.expandedRowIndex == 1,
            onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 1) null else 1 },
        )
        OverviewUsageRow(
            viewModel = viewModel,
            expanded = state.expandedRowIndex == 2,
            onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 2) null else 2 },
        )
        OverviewPlanRow(
            viewModel = viewModel,
            expanded = state.expandedRowIndex == 3,
            onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 3) null else 3 },
        )
        OverviewSubAgentRow(
            viewModel = viewModel,
            expanded = state.expandedRowIndex == 4,
            onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 4) null else 4 },
        )
        OverviewMcpRow(
            viewModel = viewModel,
            expanded = state.expandedRowIndex == 5,
            onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 5) null else 5 },
        )
        OverviewSkillRow(
            viewModel = viewModel,
            expanded = state.expandedRowIndex == 6,
            onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 6) null else 6 },
        )
        OverviewTodoRow(
            viewModel = viewModel,
            expanded = state.expandedRowIndex == 7,
            onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 7) null else 7 },
        )
        OverviewRawMessagesRow(
            viewModel = viewModel,
            rawVm = rawVm,
            expanded = state.expandedRowIndex == 8,
            onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 8) null else 8 },
        )
    }
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
