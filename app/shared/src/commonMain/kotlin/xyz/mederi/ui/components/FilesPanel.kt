package xyz.mederi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder
import compose.icons.feathericons.GitCommit
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.files_subtab_diff
import mederi.app.shared.generated.resources.files_subtab_tree
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.WorkspaceNavigationTab

/** 文件面板路由：只保留当前导航状态与已有树/变更内容接线。 */
@Composable
internal fun FilesPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(FilesTab.Tree) }
    Column(modifier.fillMaxSize()) {
        FilesPanelTabs(
            selectedTab = selectedTab,
            diffCount = viewModel.diffItems.size,
            onSelectTab = { selectedTab = it },
        )
        when (selectedTab) {
            FilesTab.Tree -> TreePanelContent(viewModel = viewModel, colors = colors)
            FilesTab.Diff -> DiffPanelContent(viewModel = viewModel, colors = colors)
        }
    }
}

/** 文件面板的两种导航，不复制 ViewModel 的文件/变更事实。 */
internal enum class FilesTab { Tree, Diff }

/** 可独立验证的文件导航视图；选中、焦点与热区由共享 Tab 原子统一。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilesPanelTabs(
    selectedTab: FilesTab,
    diffCount: Int,
    onSelectTab: (FilesTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
    ) {
        WorkspaceNavigationTab(
            text = stringResource(Res.string.files_subtab_tree),
            selected = selectedTab == FilesTab.Tree,
            onClick = { onSelectTab(FilesTab.Tree) },
            icon = FeatherIcons.Folder,
        )
        WorkspaceNavigationTab(
            text = stringResource(Res.string.files_subtab_diff),
            selected = selectedTab == FilesTab.Diff,
            onClick = { onSelectTab(FilesTab.Diff) },
            icon = FeatherIcons.GitCommit,
            detail = diffCount.takeIf { it > 0 }?.toString(),
        )
    }
}
