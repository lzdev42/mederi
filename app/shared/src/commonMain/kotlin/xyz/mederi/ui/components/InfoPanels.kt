package xyz.mederi.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import compose.icons.FeatherIcons
import compose.icons.feathericons.File
import compose.icons.feathericons.GitCommit
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_artifact_empty
import mederi.app.shared.generated.resources.dock_artifact_empty_hint
import mederi.app.shared.generated.resources.dock_diff_empty
import mederi.app.shared.generated.resources.dock_diff_empty_hint
import mederi.app.shared.generated.resources.dock_refresh_changes
import mederi.app.shared.generated.resources.diff_loading
import mederi.app.shared.generated.resources.diff_not_loaded
import mederi.app.shared.generated.resources.diff_no_session
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.DiffView
import xyz.mederi.core.contract.models.FileDiff
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.ui.ReaderPanelState
import xyz.mederi.ui.ReportKey
import xyz.mederi.ui.DiffUiState
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.WorkspaceLoadingIndicator
import xyz.mederi.ui.ArtifactItem
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.PanelEmptyState
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceNavigationTab

/** VM入口仅转发真实状态与动作，纯内容可独立预览和交互验证。 */
@Composable
internal fun DiffPanelContent(viewModel: WorkspaceViewModel, colors: MederiColors) {
    DiffQueryContent(viewModel.diffPanelState, viewModel::selectDiffFile, viewModel::refreshDiff, colors)
}

/** 纯查询反馈：错误、等待与成功空结果分别呈现，刷新仍沿当前查询范围。 */
@Composable
internal fun DiffQueryContent(
    state: DiffUiState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    colors: MederiColors,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
        state.error?.let { error ->
            ErrorBoard(stringResource(error.key, *error.args.toTypedArray()),
                showContinue = !state.loading, onContinue = onRefresh,
                continueLabel = stringResource(Res.string.dock_refresh_changes))
        }
        if (state.loading || (!state.loaded && state.error == null)) {
            Row(Modifier.padding(MederiSpacing.MD), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
                if (state.loading) WorkspaceLoadingIndicator(Modifier.size(WorkspaceStyle.IconSize))
                Text(stringResource(if (state.loading) Res.string.diff_loading else if (state.requested) Res.string.diff_not_loaded else Res.string.diff_no_session),
                    color = colors.textSecondary, style = WorkspaceStyle.Body)
            }
            if (!state.loading && state.requested) WorkspaceActionButton(stringResource(Res.string.dock_refresh_changes), onRefresh)
        }
        if (state.loaded && (state.items.isNotEmpty() || state.error == null)) Box(Modifier.weight(1f)) {
            DiffFilesContent(state.items, state.selectedPath, onSelect, onRefresh, colors, refreshing = state.loading)
        }
    }
}

@Composable
internal fun DiffFilesContent(
    diffs: List<FileDiff>,
    selectedFilePath: String?,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    colors: MederiColors,
    refreshing: Boolean = false,
) {
    if (diffs.isEmpty()) {
        PanelEmptyState(
            icon = FeatherIcons.GitCommit,
            title = stringResource(Res.string.dock_diff_empty),
            hint = stringResource(Res.string.dock_diff_empty_hint),
            action = {
                WorkspaceActionButton(stringResource(Res.string.dock_refresh_changes), onRefresh, enabled = !refreshing)
            },
        )
        return
    }
    val currentDiff = diffs.find { it.filePath == selectedFilePath } ?: diffs.first()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup().horizontalScroll(rememberScrollState())
                .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS),
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
        ) {
            diffs.forEach { diff ->
                val counts = listOfNotNull(
                    if (diff.additions > 0) "+${diff.additions}" else null,
                    if (diff.deletions > 0) "-${diff.deletions}" else null,
                ).joinToString(" ")
                WorkspaceNavigationTab(
                    text = diff.filePath.replace('\\', '/').substringAfterLast("/"),
                    selected = diff.filePath == currentDiff.filePath,
                    onClick = { onSelect(diff.filePath) },
                    detail = counts.takeIf { it.isNotEmpty() },
                )
            }
        }
        HorizontalDivider(color = colors.divider)
        DiffView(
            oldText = currentDiff.before,
            newText = currentDiff.after,
            filePath = currentDiff.filePath,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            showHeader = false,
        )
    }
}

@Composable
/**
 * 文档阅读面板（2026-11 更名"文档"）：纯阅读器，无 dock 按钮——
 * 由 openDocument（文件树点文件 / 概览"文档"行 / 对话流文档卡 / 报告查看）触达打开。
 * 项目目录浏览在"文件"（FILES）面板。
 */
internal fun ArtifactsPanelContent(viewModel: WorkspaceViewModel, colors: MederiColors) {
    ReaderPanelContent(
        state = viewModel.readerPanelState,
        onSelect = viewModel::openArtifact,
        onRetry = viewModel::retryReport,
        colors = colors,
    )
}

/** 纯阅读面板：不接VM，不读取Core/文件；状态投影和操作由Route装配。 */
@Composable
internal fun ReaderPanelContent(
    state: ReaderPanelState,
    onSelect: (String) -> Unit,
    onRetry: (ReportKey) -> Unit,
    colors: MederiColors,
    modifier: Modifier = Modifier,
) {
    val items = state.items
    if (items.isEmpty()) {
        PanelEmptyState(
            icon = FeatherIcons.File,
            title = stringResource(Res.string.dock_artifact_empty),
            hint = stringResource(Res.string.dock_artifact_empty_hint),
        )
        return
    }
    val activeItem = state.activeItem ?: return
    val showTabs = items.size > 1
    Column(modifier = modifier.fillMaxSize()) {
        if (showTabs) {
            ArtifactTabs(items, activeItem.id, onSelect, colors)
        }
        when (activeItem) {
            is ArtifactItem.Image -> ImageViewerTabContent(
                title = activeItem.title,
                imageUrl = activeItem.imageUrl,
                colors = colors,
                showTitle = !showTabs,
            )
            is ArtifactItem.Report -> ReportReaderContent(
                state = state.reportState,
                request = activeItem.request,
                title = activeItem.title,
                onRetry = { onRetry(activeItem.request.key) },
                colors = colors,
                showTitle = !showTabs,
            )
            is ArtifactItem.Text -> {
                val liveItem = activeItem
                TextReaderTabContent(
                    title = liveItem.title,
                    content = liveItem.content,
                    lineCount = liveItem.lineCount,
                    charCount = liveItem.charCount,
                    isStreaming = liveItem.isStreaming,
                    colors = colors,
                    showTitle = !showTabs,
                )
            }
        }
    }
}

/** 文档标签只转发选择，不另存activeId；已由tab命名的内容不再在工具栏重复标题。 */
@Composable
internal fun ArtifactTabs(
    items: List<ArtifactItem>,
    activeId: String?,
    onSelect: (String) -> Unit,
    colors: MederiColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup().horizontalScroll(rememberScrollState())
            .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS),
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
    ) {
        items.forEach { item ->
            WorkspaceNavigationTab(
                text = item.title,
                selected = item.id == activeId,
                onClick = { onSelect(item.id) },
            )
        }
    }
    HorizontalDivider(color = colors.divider)
}

/**
 * Token 短格式（自动切换单位）：
 * ≥1_000_000 显示一位小数 m（4_200_000 → 4.2m），
 * ≥1000 显示一位小数 k（42_800 → 42.8k），否则原值。
 */
internal fun formatTokenShort(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> {
            val m = tokens / 1_000_000
            val dec = (tokens % 1_000_000) / 100_000
            "${m}.${dec}m"
        }
        tokens >= 1000 -> {
            val k = tokens / 1000
            val dec = (tokens % 1000) / 100
            "${k}.${dec}k"
        }
        else -> tokens.toString()
    }
}

/** 参考费用展示（三位小数收敛），模块内可见（overview 行复用）。 */
internal fun formatCost(cost: Double): String {
    return if (cost < 0.001) "0.000" else (kotlin.math.round(cost * 1000) / 1000.0).toString()
}
